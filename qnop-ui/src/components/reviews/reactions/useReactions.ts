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

import { useMutation, useQueryClient } from '@tanstack/react-query';
import type {
  AnnotationListResponse,
  AnnotationView,
  CommentListResponse,
} from '../../../api/generated';
import { annotationsApi } from '../../../api/config';
import { annotationKeys } from '../../../api/hooks/useAnnotations';
import { commentKeys } from '../../../api/hooks/useComments';
import { apiErrorCode } from '../../../utils/apiError';
import { useAuthStore } from '../../../stores/authStore';
import type { Notify } from '../../admin/layout/useToast';
import { toggleReactionGroup } from './reactionGroups';

/** The toggle's input: the chip's emoji and whether the viewer already carries it. */
export interface ReactionToggle {
  emoji: string;
  reacted: boolean;
}

function notifyToggleError(error: unknown, notify: Notify) {
  notify(
    apiErrorCode(error) === 'REVIEW_CLOSED'
      ? 'The review is closed — reactions are read-only.'
      : 'Could not update the reaction.',
    'error',
  );
}

/**
 * Toggles the viewer's emoji reaction on an annotation (issue #410),
 * optimistically: every cached annotation list flips the chip immediately and
 * rolls back on error; the server truth is refetched on settle. The document
 * detail (with its PDF binaries) is deliberately NOT touched (#403 lesson).
 */
export function useToggleAnnotationReaction(annotationId: string, notify: Notify) {
  const queryClient = useQueryClient();
  const viewerName = useAuthStore((state) => state.displayName) ?? 'You';
  return useMutation({
    mutationFn: async ({ emoji, reacted }: ReactionToggle) => {
      if (reacted) {
        await annotationsApi.unreactFromAnnotation({ annotationId, emoji });
      } else {
        await annotationsApi.reactToAnnotation({ annotationId, emoji });
      }
    },
    onMutate: async ({ emoji }: ReactionToggle) => {
      await queryClient.cancelQueries({ queryKey: annotationKeys.all });
      const snapshots = queryClient.getQueriesData<AnnotationListResponse>({
        queryKey: annotationKeys.all,
      });
      snapshots.forEach(([key, data]) => {
        if (!data?.annotations) return;
        queryClient.setQueryData<AnnotationListResponse>(key, {
          ...data,
          annotations: data.annotations.map((annotation: AnnotationView) =>
            annotation.id === annotationId
              ? {
                  ...annotation,
                  reactions: toggleReactionGroup(annotation.reactions, emoji, viewerName),
                }
              : annotation,
          ),
        });
      });
      return { snapshots };
    },
    onError: (error, _toggle, context) => {
      context?.snapshots.forEach(([key, data]) => queryClient.setQueryData(key, data));
      notifyToggleError(error, notify);
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: annotationKeys.all });
    },
  });
}

/**
 * Toggles the viewer's emoji reaction on a comment (issue #410) — the same
 * optimistic flip against the annotation's cached thread.
 */
export function useToggleCommentReaction(annotationId: string, notify: Notify) {
  const queryClient = useQueryClient();
  const viewerName = useAuthStore((state) => state.displayName) ?? 'You';
  return useMutation({
    mutationFn: async ({ commentId, emoji, reacted }: ReactionToggle & { commentId: string }) => {
      if (reacted) {
        await annotationsApi.unreactFromComment({ commentId, emoji });
      } else {
        await annotationsApi.reactToComment({ commentId, emoji });
      }
    },
    onMutate: async ({ commentId, emoji }) => {
      const key = commentKeys.list(annotationId);
      await queryClient.cancelQueries({ queryKey: key });
      const snapshot = queryClient.getQueryData<CommentListResponse>(key);
      if (snapshot?.comments) {
        queryClient.setQueryData<CommentListResponse>(key, {
          ...snapshot,
          comments: snapshot.comments.map((comment) =>
            comment.id === commentId
              ? {
                  ...comment,
                  reactions: toggleReactionGroup(comment.reactions, emoji, viewerName),
                }
              : comment,
          ),
        });
      }
      return { snapshot, key };
    },
    onError: (error, _toggle, context) => {
      if (context) queryClient.setQueryData(context.key, context.snapshot);
      notifyToggleError(error, notify);
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: commentKeys.list(annotationId) });
    },
  });
}
