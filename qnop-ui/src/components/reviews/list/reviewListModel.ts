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

import type { DocumentSummary } from '../../../api/generated';

/** The caller's role on a review — owner is structural (ADR-0011), everyone else reviews. */
export type ReviewRole = 'owner' | 'reviewer' | 'observer';

/**
 * The caller's part in a review.
 *
 * <p>`observer` only occurs in an admin's moderation listing (issue #563), where
 * rows appear that the caller has no part in at all. The server decides it —
 * participation can come through a team, which the row itself does not show.
 */
export function roleOf(review: DocumentSummary, userId: string | null): ReviewRole {
  if (review.participating === false) return 'observer';
  return review.ownerId === userId ? 'owner' : 'reviewer';
}

/** Decided/total progress of a review; null when it has no annotations yet. */
export function progressOf(review: DocumentSummary): { resolved: number; total: number } | null {
  if (review.annotationCount === 0) return null;
  return {
    resolved: review.annotationCount - review.openAnnotationCount,
    total: review.annotationCount,
  };
}
