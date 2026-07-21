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
import type { AnnotationListResponse, AnnotationView } from '../generated';
import { AnnotationStatus, PlacementStatus } from '../generated';
import {
  annotationKeys,
  hasPendingPlacement,
  settledSince,
  useAnnotations,
  useCreateAnnotation,
  useResolveAnnotation,
} from './useAnnotations';
import { commentKeys } from './useComments';
import { annotationsApi } from '../config';

vi.mock('../config', () => ({
  annotationsApi: {
    listAnnotations: vi.fn(),
    createAnnotation: vi.fn(),
    resolveAnnotation: vi.fn(),
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

  // Live re-anchoring (issue #553): when the polled list shows a placement
  // settling, the annotation's comment thread is refreshed alongside.
  it('invalidates the comment thread of a placement that settled', async () => {
    const pending: AnnotationListResponse = {
      annotations: [
        placement('a1', PlacementStatus.Pending),
        placement('a2', PlacementStatus.Placed),
      ],
    };
    const settled: AnnotationListResponse = {
      annotations: [
        placement('a1', PlacementStatus.Moved),
        placement('a2', PlacementStatus.Placed),
      ],
    };
    vi.mocked(annotationsApi.listAnnotations)
      .mockResolvedValueOnce({ data: pending } as Awaited<
        ReturnType<typeof annotationsApi.listAnnotations>
      >)
      .mockResolvedValueOnce({ data: settled } as Awaited<
        ReturnType<typeof annotationsApi.listAnnotations>
      >);

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    const localWrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useAnnotations(DOC_ID, 2), { wrapper: localWrapper });
    await waitFor(() =>
      expect(result.current.data?.annotations[0].placementStatus).toBe(PlacementStatus.Pending),
    );

    // A poll observes the settle — simulate it by refetching the query.
    await result.current.refetch();
    await waitFor(() =>
      expect(result.current.data?.annotations[0].placementStatus).toBe(PlacementStatus.Moved),
    );

    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith({ queryKey: commentKeys.list('a1') }),
    );
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: commentKeys.list('a2') });
  });
});

function placement(id: string, placementStatus: PlacementStatus): AnnotationView {
  return {
    id,
    documentId: DOC_ID,
    authorId: 'u1',
    status: AnnotationStatus.Open,
    placementStatus,
    commentCount: 0,
    reactions: [],
    createdAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-01T10:00:00Z',
  };
}

describe('hasPendingPlacement', () => {
  it('is true only while some placement is PENDING', () => {
    expect(hasPendingPlacement(undefined)).toBe(false);
    expect(hasPendingPlacement([placement('a1', PlacementStatus.Placed)])).toBe(false);
    expect(
      hasPendingPlacement([
        placement('a1', PlacementStatus.Placed),
        placement('a2', PlacementStatus.Pending),
      ]),
    ).toBe(true);
  });
});

describe('settledSince', () => {
  it('names exactly the previously pending placements that are no longer pending', () => {
    const previous = new Set(['a1', 'a2']);
    const now = [
      placement('a1', PlacementStatus.Moved),
      placement('a2', PlacementStatus.Pending),
      placement('a3', PlacementStatus.Orphaned),
    ];
    expect(settledSince(previous, now)).toEqual(['a1']);
  });

  it('treats a vanished annotation as settled', () => {
    expect(settledSince(new Set(['gone']), [])).toEqual(['gone']);
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

describe('useResolveAnnotation', () => {
  it('posts the resolution with its closing note', async () => {
    vi.mocked(annotationsApi.resolveAnnotation).mockResolvedValue({ data: { id: 'a1' } } as Awaited<
      ReturnType<typeof annotationsApi.resolveAnnotation>
    >);

    const { result } = renderHook(() => useResolveAnnotation(), { wrapper });
    await result.current.mutateAsync({ annotationId: 'a1', note: 'Addressed in v2.' });

    expect(annotationsApi.resolveAnnotation).toHaveBeenCalledWith({
      annotationId: 'a1',
      annotationResolveRequest: { note: 'Addressed in v2.' },
    });
  });

  it('omits the request body when there is no note', async () => {
    vi.mocked(annotationsApi.resolveAnnotation).mockResolvedValue({ data: { id: 'a1' } } as Awaited<
      ReturnType<typeof annotationsApi.resolveAnnotation>
    >);

    const { result } = renderHook(() => useResolveAnnotation(), { wrapper });
    await result.current.mutateAsync({ annotationId: 'a1' });

    expect(annotationsApi.resolveAnnotation).toHaveBeenCalledWith({
      annotationId: 'a1',
      annotationResolveRequest: undefined,
    });
  });
});
