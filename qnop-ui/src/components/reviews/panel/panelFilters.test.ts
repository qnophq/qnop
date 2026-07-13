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

import { describe, expect, it } from 'vitest';
import type { AnnotationView } from '../../../api/generated';
import {
  AnnotationPriority,
  AnnotationStatus,
  AnnotationType,
  PlacementStatus,
} from '../../../api/generated';
import {
  EMPTY_FILTERS,
  activeFacetCount,
  comparePanelOrder,
  matchesFilters,
  reanchorSummary,
} from './panelFilters';

const annotation = (overrides: Partial<AnnotationView> = {}): AnnotationView => ({
  id: 'a1',
  documentId: 'd1',
  authorId: 'u1',
  status: AnnotationStatus.Open,
  placementStatus: 'PLACED' as AnnotationView['placementStatus'],
  commentCount: 1,
  reactions: [],
  anchor: {
    region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.05 } },
    textQuote: { quote: 'the disputed clause' },
  },
  firstComment: 'Please rephrase this.',
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
  ...overrides,
});

describe('matchesFilters', () => {
  it('lets everything through on the empty filter', () => {
    expect(matchesFilters(annotation(), EMPTY_FILTERS, 'Paul')).toBe(true);
  });

  it('narrows by status, type, priority and author', () => {
    expect(matchesFilters(annotation(), { ...EMPTY_FILTERS, status: 'resolved' }, 'Paul')).toBe(
      false,
    );
    expect(
      matchesFilters(
        annotation({ status: AnnotationStatus.Resolved }),
        { ...EMPTY_FILTERS, status: 'resolved' },
        'Paul',
      ),
    ).toBe(true);
    expect(
      matchesFilters(annotation(), { ...EMPTY_FILTERS, type: AnnotationType.Risk }, 'Paul'),
    ).toBe(false);
    expect(
      matchesFilters(
        annotation({ type: AnnotationType.Risk }),
        { ...EMPTY_FILTERS, type: AnnotationType.Risk },
        'Paul',
      ),
    ).toBe(true);
    expect(
      matchesFilters(
        annotation({ priority: AnnotationPriority.High }),
        { ...EMPTY_FILTERS, priority: AnnotationPriority.Low },
        'Paul',
      ),
    ).toBe(false);
    expect(matchesFilters(annotation(), { ...EMPTY_FILTERS, author: 'other' }, 'Paul')).toBe(false);
    expect(matchesFilters(annotation(), { ...EMPTY_FILTERS, author: 'u1' }, 'Paul')).toBe(true);
  });

  it('searches quote, opening text and author name, case-insensitively', () => {
    expect(matchesFilters(annotation(), { ...EMPTY_FILTERS, query: 'DISPUTED' }, 'Paul')).toBe(
      true,
    );
    expect(matchesFilters(annotation(), { ...EMPTY_FILTERS, query: 'rephrase' }, 'Paul')).toBe(
      true,
    );
    expect(matchesFilters(annotation(), { ...EMPTY_FILTERS, query: 'paul' }, 'Paul Richter')).toBe(
      true,
    );
    expect(matchesFilters(annotation(), { ...EMPTY_FILTERS, query: 'kalinda' }, 'Paul')).toBe(
      false,
    );
  });

  it('matches the opening text stripped of Markdown, not its syntax (#427)', () => {
    const a = annotation({ firstComment: '**Please** rephrase the _liability_ clause' });
    expect(matchesFilters(a, { ...EMPTY_FILTERS, query: 'liability' }, 'Paul')).toBe(true);
    expect(matchesFilters(a, { ...EMPTY_FILTERS, query: '_liability_' }, 'Paul')).toBe(false);
  });
});

describe('activeFacetCount', () => {
  it('counts only facets that deviate from any', () => {
    expect(activeFacetCount(EMPTY_FILTERS)).toBe(0);
    expect(
      activeFacetCount({
        ...EMPTY_FILTERS,
        status: 'open',
        type: AnnotationType.Question,
        author: 'u1',
        query: 'ignored',
      }),
    ).toBe(3);
  });
});

describe('placement facet & re-anchoring summary (issue #326)', () => {
  const placed = annotation({ id: 'p1', placementStatus: PlacementStatus.Placed });
  const moved = annotation({ id: 'm1', placementStatus: PlacementStatus.Moved });
  const orphaned = annotation({ id: 'o1', placementStatus: PlacementStatus.Orphaned });
  const failed = annotation({ id: 'f1', placementStatus: PlacementStatus.Failed });

  it("'attention' keeps every outcome a human still owes a look", () => {
    const filters = { ...EMPTY_FILTERS, placement: 'attention' as const };
    expect(matchesFilters(placed, filters, '')).toBe(false);
    expect(matchesFilters(moved, filters, '')).toBe(true);
    expect(matchesFilters(orphaned, filters, '')).toBe(true);
    expect(matchesFilters(failed, filters, '')).toBe(true);
  });

  it('moved and orphaned narrow to their exact outcome', () => {
    expect(matchesFilters(moved, { ...EMPTY_FILTERS, placement: 'moved' }, '')).toBe(true);
    expect(matchesFilters(orphaned, { ...EMPTY_FILTERS, placement: 'moved' }, '')).toBe(false);
    expect(matchesFilters(orphaned, { ...EMPTY_FILTERS, placement: 'orphaned' }, '')).toBe(true);
  });

  it('counts as an active facet', () => {
    expect(activeFacetCount({ ...EMPTY_FILTERS, placement: 'attention' })).toBe(1);
  });

  it('summarises what re-anchoring left for humans', () => {
    expect(reanchorSummary([placed, moved, orphaned, failed])).toEqual({
      moved: 1,
      orphaned: 2,
      total: 3,
    });
    expect(reanchorSummary([placed]).total).toBe(0);
  });
});

describe('comparePanelOrder (issue #481)', () => {
  const doc = (id: string, createdAt: string) => annotation({ id, anchor: undefined, createdAt });
  const placed = (id: string, surfaceIndex: number, y = 0.1) =>
    annotation({
      id,
      anchor: { region: { surfaceIndex, box: { x: 0.1, y, width: 0.2, height: 0.1 } } },
    });

  it('leads with document-scoped remarks (oldest first), then anchored by position', () => {
    const list = [
      placed('p2', 1),
      doc('d-late', '2026-07-02T10:00:00Z'),
      placed('p1', 0),
      doc('d-early', '2026-07-01T10:00:00Z'),
    ];
    expect(list.sort(comparePanelOrder).map((a) => a.id)).toEqual([
      'd-early',
      'd-late',
      'p1',
      'p2',
    ]);
  });
});
