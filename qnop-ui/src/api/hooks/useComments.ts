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

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { CommentListResponse } from '../generated';
import { annotationsApi } from '../config';
import { annotationKeys } from './useAnnotations';

export const commentKeys = {
  all: ['comments'] as const,
  list: (annotationId: string) => [...commentKeys.all, 'list', annotationId] as const,
};

/** An annotation's comment thread, oldest first (flat, single level). */
export function useComments(annotationId: string, enabled = true) {
  return useQuery<CommentListResponse>({
    queryKey: commentKeys.list(annotationId),
    queryFn: async () => {
      const response = await annotationsApi.listComments({ annotationId });
      return response.data;
    },
    enabled,
  });
}

/**
 * Appends a comment to the annotation's thread. Also invalidates the annotation
 * lists because their `commentCount` changes with the new comment.
 */
export function useAddComment(annotationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: string) => {
      const response = await annotationsApi.addComment({
        annotationId,
        commentCreateRequest: { body },
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: commentKeys.list(annotationId) });
      queryClient.invalidateQueries({ queryKey: annotationKeys.all });
    },
  });
}
