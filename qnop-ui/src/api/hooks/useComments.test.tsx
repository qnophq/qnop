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
import type { CommentListResponse } from '../generated';
import { commentKeys, useAddComment, useComments } from './useComments';
import { annotationsApi } from '../config';

vi.mock('../config', () => ({
  annotationsApi: {
    listComments: vi.fn(),
    addComment: vi.fn(),
  },
}));

const ANNOTATION_ID = 'aaaa1111-0000-0000-0000-000000000001';

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('commentKeys', () => {
  it('namespaces the thread per annotation', () => {
    expect(commentKeys.list(ANNOTATION_ID)).toEqual(['comments', 'list', ANNOTATION_ID]);
  });
});

describe('useComments', () => {
  it('fetches the thread oldest first', async () => {
    const empty: CommentListResponse = { comments: [] };
    vi.mocked(annotationsApi.listComments).mockResolvedValue({
      data: empty,
    } as Awaited<ReturnType<typeof annotationsApi.listComments>>);

    const { result } = renderHook(() => useComments(ANNOTATION_ID), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(annotationsApi.listComments).toHaveBeenCalledWith({ annotationId: ANNOTATION_ID });
  });

  it('stays idle when disabled', () => {
    const { result } = renderHook(() => useComments(ANNOTATION_ID, false), { wrapper });

    expect(result.current.fetchStatus).toBe('idle');
    expect(annotationsApi.listComments).not.toHaveBeenCalled();
  });
});

describe('useAddComment', () => {
  it('wraps the body into the request', async () => {
    vi.mocked(annotationsApi.addComment).mockResolvedValue({ data: { id: 'c1' } } as Awaited<
      ReturnType<typeof annotationsApi.addComment>
    >);

    const { result } = renderHook(() => useAddComment(ANNOTATION_ID), { wrapper });
    await result.current.mutateAsync('Looks good to me');

    expect(annotationsApi.addComment).toHaveBeenCalledWith({
      annotationId: ANNOTATION_ID,
      commentCreateRequest: { body: 'Looks good to me' },
    });
  });
});
