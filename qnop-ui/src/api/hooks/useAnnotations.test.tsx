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
import type { AnnotationListResponse } from '../generated';
import {
  annotationKeys,
  useAnnotations,
  useCreateAnnotation,
  useDecideAnnotation,
} from './useAnnotations';
import { annotationsApi } from '../config';

vi.mock('../config', () => ({
  annotationsApi: {
    listAnnotations: vi.fn(),
    createAnnotation: vi.fn(),
    decideAnnotation: vi.fn(),
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

describe('annotationKeys', () => {
  it('namespaces the list per document and version', () => {
    expect(annotationKeys.list(DOC_ID, 3)).toEqual(['annotations', 'list', DOC_ID, 3]);
  });
});

describe('useAnnotations', () => {
  it('fetches annotations resolved against the given version', async () => {
    const empty: AnnotationListResponse = { annotations: [] };
    vi.mocked(annotationsApi.listAnnotations).mockResolvedValue({
      data: empty,
    } as Awaited<ReturnType<typeof annotationsApi.listAnnotations>>);

    const { result } = renderHook(() => useAnnotations(DOC_ID, 2), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(annotationsApi.listAnnotations).toHaveBeenCalledWith({ documentId: DOC_ID, version: 2 });
  });

  it('stays idle until the version is known', () => {
    const { result } = renderHook(() => useAnnotations(DOC_ID, undefined), { wrapper });

    expect(result.current.fetchStatus).toBe('idle');
    expect(annotationsApi.listAnnotations).not.toHaveBeenCalled();
  });
});

describe('useCreateAnnotation', () => {
  it('posts the anchor and drawn version', async () => {
    vi.mocked(annotationsApi.createAnnotation).mockResolvedValue({ data: { id: 'a1' } } as Awaited<
      ReturnType<typeof annotationsApi.createAnnotation>
    >);

    const { result } = renderHook(() => useCreateAnnotation(DOC_ID), { wrapper });
    const request = {
      versionNumber: 1,
      anchor: { region: { surfaceIndex: 0, box: { x: 0.1, y: 0.2, width: 0.3, height: 0.05 } } },
      comment: 'Please fix',
    };
    await result.current.mutateAsync(request);

    expect(annotationsApi.createAnnotation).toHaveBeenCalledWith({
      documentId: DOC_ID,
      annotationCreateRequest: request,
    });
  });
});

describe('useDecideAnnotation', () => {
  it('posts the decision', async () => {
    vi.mocked(annotationsApi.decideAnnotation).mockResolvedValue({ data: { id: 'a1' } } as Awaited<
      ReturnType<typeof annotationsApi.decideAnnotation>
    >);

    const { result } = renderHook(() => useDecideAnnotation(), { wrapper });
    await result.current.mutateAsync({ annotationId: 'a1', decision: 'ACCEPTED' });

    expect(annotationsApi.decideAnnotation).toHaveBeenCalledWith({
      annotationId: 'a1',
      annotationDecisionRequest: { decision: 'ACCEPTED' },
    });
  });
});
