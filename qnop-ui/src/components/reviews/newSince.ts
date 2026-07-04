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

import type { AnnotationView, CommentView } from '../../api/generated';

/**
 * The unseen rules (issue #307), shared by the panel, the focus card and the
 * task board. `previousSeenAt` is the PREVIOUS visit returned by the visit
 * stamp — null on the first visit, when nothing may read as new.
 */

/** Timestamp comparison over epoch millis — ISO offset spellings vary (Z vs +00:00). */
function isAfter(instant: string, baseline: string): boolean {
  return new Date(instant).getTime() > new Date(baseline).getTime();
}

/** A foreign annotation created after the previous visit. */
export function isNewAnnotation(
  annotation: AnnotationView,
  previousSeenAt: string | null,
  userId: string | null,
): boolean {
  if (!previousSeenAt || annotation.authorId === userId) return false;
  return isAfter(annotation.createdAt, previousSeenAt);
}

/**
 * Foreign comments arrived after the previous visit. Relies on the server's
 * `latestCommentFromOthersAt` (newest comment by someone else), so the check
 * works from the list without loading threads.
 */
export function hasNewComments(annotation: AnnotationView, previousSeenAt: string | null): boolean {
  if (!previousSeenAt || !annotation.latestCommentFromOthersAt) return false;
  return isAfter(annotation.latestCommentFromOthersAt, previousSeenAt);
}

/** A foreign comment inside an opened thread that arrived after the previous visit. */
export function isNewComment(
  comment: CommentView,
  previousSeenAt: string | null,
  userId: string | null,
): boolean {
  if (!previousSeenAt || comment.authorId === userId) return false;
  return isAfter(comment.createdAt, previousSeenAt);
}

/** Anything unseen on this annotation — the card-level cue. */
export function isUnseen(
  annotation: AnnotationView,
  previousSeenAt: string | null,
  userId: string | null,
): boolean {
  return (
    isNewAnnotation(annotation, previousSeenAt, userId) ||
    hasNewComments(annotation, previousSeenAt)
  );
}
