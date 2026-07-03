/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { RenderedDocumentResponse } from '../generated';
import { ExtractionStatus } from '../generated';
import {
  documentKeys,
  useDocument,
  useDocumentVersions,
  useOriginalPdf,
  useRenderedDocument,
} from './useDocuments';
import { axiosInstance, documentsApi } from '../config';

vi.mock('../config', () => ({
  documentsApi: {
    getDocument: vi.fn(),
    listDocumentVersions: vi.fn(),
    getRenderedDocument: vi.fn(),
  },
  axiosInstance: {
    get: vi.fn(),
  },
}));

const DOC_ID = '3f6f6f6f-0000-0000-0000-000000000001';

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('documentKeys', () => {
  it('namespaces per document and version', () => {
    expect(documentKeys.detail(DOC_ID)).toEqual(['documents', 'detail', DOC_ID]);
    expect(documentKeys.rendered(DOC_ID, 2)).toEqual(['documents', 'rendered', DOC_ID, 2]);
    expect(documentKeys.original(DOC_ID, 2)).toEqual(['documents', 'original', DOC_ID, 2]);
  });
});

describe('useDocument', () => {
  it('fetches the document metadata', async () => {
    vi.mocked(documentsApi.getDocument).mockResolvedValue({
      data: { id: DOC_ID, title: 'Contract', latestVersionNumber: 1 },
    } as Awaited<ReturnType<typeof documentsApi.getDocument>>);

    const { result } = renderHook(() => useDocument(DOC_ID), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(documentsApi.getDocument).toHaveBeenCalledWith({ documentId: DOC_ID });
    expect(result.current.data?.title).toBe('Contract');
  });
});

describe('useDocumentVersions', () => {
  it('fetches the version list', async () => {
    vi.mocked(documentsApi.listDocumentVersions).mockResolvedValue({
      data: { versions: [{ versionNumber: 1, extractionStatus: ExtractionStatus.Ready }] },
    } as Awaited<ReturnType<typeof documentsApi.listDocumentVersions>>);

    const { result } = renderHook(() => useDocumentVersions(DOC_ID, 1), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(documentsApi.listDocumentVersions).toHaveBeenCalledWith({ documentId: DOC_ID });
    expect(result.current.data?.versions).toHaveLength(1);
  });

  // Regression for issue #300: right after a new-version upload the cached list may
  // not contain the watched version yet. Polling must run BOTH while the version is
  // missing from the list AND while it is PENDING — otherwise the viewer sticks on
  // "Processing document…" until a manual reload.
  describe('extraction polling (issue #300)', () => {
    const v1Ready = { versionNumber: 1, extractionStatus: ExtractionStatus.Ready };
    const v2Pending = { versionNumber: 2, extractionStatus: ExtractionStatus.Pending };
    const v2Ready = { versionNumber: 2, extractionStatus: ExtractionStatus.Ready };

    afterEach(() => {
      vi.useRealTimers();
    });

    it('polls while the watched version is missing, then while PENDING, and stops on READY', async () => {
      vi.useFakeTimers();
      const responses = [
        { versions: [v1Ready] }, // stale list — v2 uploaded but not refetched yet
        { versions: [v1Ready, v2Pending] }, // v2 appears, extraction running
        { versions: [v1Ready, v2Ready] }, // extraction finished
      ];
      let call = 0;
      vi.mocked(documentsApi.listDocumentVersions).mockImplementation(
        async () =>
          ({ data: responses[Math.min(call++, responses.length - 1)] }) as Awaited<
            ReturnType<typeof documentsApi.listDocumentVersions>
          >,
      );

      const { result } = renderHook(() => useDocumentVersions(DOC_ID, 2), { wrapper });

      // Initial fetch resolves with the stale list (v2 missing).
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(documentsApi.listDocumentVersions).toHaveBeenCalledTimes(1);

      // The watched version is absent → the list is stale → a poll tick must fire.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2600);
      });
      expect(documentsApi.listDocumentVersions).toHaveBeenCalledTimes(2);

      // v2 is PENDING → keep polling until READY.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2600);
      });
      expect(documentsApi.listDocumentVersions).toHaveBeenCalledTimes(3);
      expect(result.current.data?.versions).toContainEqual(v2Ready);

      // READY → polling stops.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(6000);
      });
      expect(documentsApi.listDocumentVersions).toHaveBeenCalledTimes(3);
    });

    it('does not poll without a watched version', async () => {
      vi.useFakeTimers();
      vi.mocked(documentsApi.listDocumentVersions).mockResolvedValue({
        data: { versions: [v1Ready] },
      } as Awaited<ReturnType<typeof documentsApi.listDocumentVersions>>);

      renderHook(() => useDocumentVersions(DOC_ID, undefined), { wrapper });

      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(6000);
      });
      expect(documentsApi.listDocumentVersions).toHaveBeenCalledTimes(1);
    });

    it('does not poll forever for an out-of-range version (stale/tampered URL)', async () => {
      vi.useFakeTimers();
      // Only v1 exists; a URL asking for v999 must not keep the list polling —
      // that version is more than one above the max and can never appear.
      vi.mocked(documentsApi.listDocumentVersions).mockResolvedValue({
        data: { versions: [v1Ready] },
      } as Awaited<ReturnType<typeof documentsApi.listDocumentVersions>>);

      renderHook(() => useDocumentVersions(DOC_ID, 999), { wrapper });

      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(documentsApi.listDocumentVersions).toHaveBeenCalledTimes(1);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(6000);
      });
      expect(documentsApi.listDocumentVersions).toHaveBeenCalledTimes(1);
    });
  });
});

describe('useRenderedDocument', () => {
  it('fetches the rendered representation when enabled', async () => {
    const empty: RenderedDocumentResponse = { surfaces: [] };
    vi.mocked(documentsApi.getRenderedDocument).mockResolvedValue({
      data: empty,
    } as Awaited<ReturnType<typeof documentsApi.getRenderedDocument>>);

    const { result } = renderHook(() => useRenderedDocument(DOC_ID, 1, true), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(documentsApi.getRenderedDocument).toHaveBeenCalledWith({
      documentId: DOC_ID,
      versionNumber: 1,
    });
  });

  it('stays idle while extraction is not READY', () => {
    const { result } = renderHook(() => useRenderedDocument(DOC_ID, 1, false), { wrapper });

    expect(result.current.fetchStatus).toBe('idle');
    expect(documentsApi.getRenderedDocument).not.toHaveBeenCalled();
  });
});

describe('useOriginalPdf', () => {
  it('downloads the original bytes outside the generated contract', async () => {
    const bytes = new ArrayBuffer(8);
    vi.mocked(axiosInstance.get).mockResolvedValue({ data: bytes });

    const { result } = renderHook(() => useOriginalPdf(DOC_ID, 2), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(axiosInstance.get).toHaveBeenCalledWith(`/documents/${DOC_ID}/versions/2/original`, {
      responseType: 'arraybuffer',
    });
    expect(result.current.data).toBe(bytes);
  });

  it('stays idle until the version number is known', () => {
    const { result } = renderHook(() => useOriginalPdf(DOC_ID, undefined), { wrapper });

    expect(result.current.fetchStatus).toBe('idle');
    expect(axiosInstance.get).not.toHaveBeenCalled();
  });
});
