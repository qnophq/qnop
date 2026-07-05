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
import { AnnotationPriority, AnnotationStatus, AnnotationType } from '../../../api/generated';

/** The panel's filter facets (issue #403); `null`/`'all'`/`''` mean "any". */
export interface AnnotationFilters {
  status: 'all' | 'open' | 'resolved';
  type: AnnotationType | null;
  priority: AnnotationPriority | null;
  /** The author's principal id. */
  author: string | null;
  /** Full-text needle over quote, opening text and author name. */
  query: string;
}

export const EMPTY_FILTERS: AnnotationFilters = {
  status: 'all',
  type: null,
  priority: null,
  author: null,
  query: '',
};

/** How many facets deviate from "any" — the filter button's badge (the query is visible in the search field itself). */
export function activeFacetCount(filters: AnnotationFilters): number {
  return (
    (filters.status !== 'all' ? 1 : 0) +
    (filters.type !== null ? 1 : 0) +
    (filters.priority !== null ? 1 : 0) +
    (filters.author !== null ? 1 : 0)
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
  if (filters.type !== null && annotation.type !== filters.type) return false;
  if (filters.priority !== null && annotation.priority !== filters.priority) return false;
  if (filters.author !== null && annotation.authorId !== filters.author) return false;
  const needle = filters.query.trim().toLowerCase();
  if (!needle) return true;
  return (
    (annotation.anchor?.textQuote?.quote ?? '').toLowerCase().includes(needle) ||
    (annotation.firstComment ?? '').toLowerCase().includes(needle) ||
    authorName.toLowerCase().includes(needle)
  );
}
