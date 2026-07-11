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

import type { AnnotationView } from '../../../api/generated';
import {
  AnnotationPriority,
  AnnotationStatus,
  AnnotationType,
  PlacementStatus,
} from '../../../api/generated';
import { stripMarkdown } from '../../../utils/markdown';

/** The panel's filter facets (issue #403); `null`/`'all'`/`''` mean "any". */
export interface AnnotationFilters {
  status: 'all' | 'open' | 'resolved';
  /** Re-anchoring outcome (ADR-0009, issue #326); 'attention' = MOVED/ORPHANED/FAILED. */
  placement: 'all' | 'attention' | 'moved' | 'orphaned';
  type: AnnotationType | null;
  priority: AnnotationPriority | null;
  /** The author's principal id. */
  author: string | null;
  /** Full-text needle over quote, opening text and author name. */
  query: string;
}

export const EMPTY_FILTERS: AnnotationFilters = {
  status: 'all',
  placement: 'all',
  type: null,
  priority: null,
  author: null,
  query: '',
};

/** How many facets deviate from "any" — the filter button's badge (the query is visible in the search field itself). */
export function activeFacetCount(filters: AnnotationFilters): number {
  return (
    (filters.status !== 'all' ? 1 : 0) +
    (filters.placement !== 'all' ? 1 : 0) +
    (filters.type !== null ? 1 : 0) +
    (filters.priority !== null ? 1 : 0) +
    (filters.author !== null ? 1 : 0)
  );
}

/** The re-anchoring facet (issue #326): which placement outcomes pass. */
function matchesPlacement(
  annotation: AnnotationView,
  placement: AnnotationFilters['placement'],
): boolean {
  if (placement === 'all') return true;
  const status = annotation.placementStatus;
  if (placement === 'moved') return status === PlacementStatus.Moved;
  if (placement === 'orphaned') return status === PlacementStatus.Orphaned;
  return (
    status === PlacementStatus.Moved ||
    status === PlacementStatus.Orphaned ||
    status === PlacementStatus.Failed
  );
}

/** Whether the annotation survives every facet plus the full-text needle. */
export function matchesFilters(
  annotation: AnnotationView,
  filters: AnnotationFilters,
  authorName: string,
): boolean {
  if (filters.status === 'open' && annotation.status !== AnnotationStatus.Open) return false;
  if (filters.status === 'resolved' && annotation.status === AnnotationStatus.Open) return false;
  if (!matchesPlacement(annotation, filters.placement)) return false;
  if (filters.type !== null && annotation.type !== filters.type) return false;
  if (filters.priority !== null && annotation.priority !== filters.priority) return false;
  if (filters.author !== null && annotation.authorId !== filters.author) return false;
  const needle = filters.query.trim().toLowerCase();
  if (!needle) return true;
  // The opening comment is Markdown (issue #427); match its stripped plain text
  // so search hits words, not `**` and `[](…)` syntax. The quote is plain.
  return (
    (annotation.anchor?.textQuote?.quote ?? '').toLowerCase().includes(needle) ||
    stripMarkdown(annotation.firstComment).toLowerCase().includes(needle) ||
    authorName.toLowerCase().includes(needle)
  );
}

/** What the current version's re-anchoring left for humans (issue #326). */
export function reanchorSummary(annotations: AnnotationView[]) {
  const moved = annotations.filter(
    (annotation) => annotation.placementStatus === PlacementStatus.Moved,
  ).length;
  const orphaned = annotations.filter(
    (annotation) =>
      annotation.placementStatus === PlacementStatus.Orphaned ||
      annotation.placementStatus === PlacementStatus.Failed,
  ).length;
  return { moved, orphaned, total: moved + orphaned };
}
