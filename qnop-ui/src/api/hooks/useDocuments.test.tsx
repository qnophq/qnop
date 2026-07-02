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
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
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
