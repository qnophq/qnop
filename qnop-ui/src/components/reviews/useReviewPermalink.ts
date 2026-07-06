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

import { useCallback } from 'react';
import { useParams } from 'react-router-dom';

/** The document view a permalink should reopen in (issue #412). */
export type PermalinkView = 'panel' | 'focus';

/** Builds an absolute permalink to an annotation, optionally to one comment. */
export type BuildPermalink = (annotationId: string, commentId?: string) => string;

/**
 * A builder for shareable annotation/comment permalinks (issue #412). The URL
 * carries the pretty route segment as-is — a slug when the page was opened by
 * slug (issue #411) — plus `?annotation=` (and `?comment=` for a single
 * comment) and the current document view, so a copied link reopens the exact
 * target the sharer sees. It only builds URLs; the server still enforces
 * access, and the link carries no extra authorization (anti-enumeration
 * unchanged).
 *
 * Call at the page level (inside the route), where `useParams` is available;
 * pass the returned builder down to the copy-link affordances.
 */
export function useReviewPermalink(view?: PermalinkView): BuildPermalink {
  const { documentId = '' } = useParams();
  return useCallback(
    (annotationId, commentId) => {
      const params = new URLSearchParams();
      params.set('annotation', annotationId);
      if (commentId) params.set('comment', commentId);
      if (view) params.set('view', view);
      return `${window.location.origin}/reviews/${documentId}?${params.toString()}`;
    },
    [documentId, view],
  );
}
