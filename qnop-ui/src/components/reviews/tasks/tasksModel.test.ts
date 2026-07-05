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
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { columnOf, matchesQuery, parseTaskFilter, taskKeys, taskTitle } from './tasksModel';

const annotation = (overrides: Partial<AnnotationView> = {}): AnnotationView => ({
  id: 'a1',
  documentId: 'd1',
  authorId: 'u1',
  status: AnnotationStatus.Open,
  placementStatus: PlacementStatus.Placed,
  anchor: {
    region: { surfaceIndex: 2, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.05 } },
    textQuote: { quote: 'the disputed clause' },
  },
  firstComment: 'Please extend the payment target',
  commentCount: 1,
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
  ...overrides,
});

describe('columnOf', () => {
  it('places a fresh OPEN annotation (only its mandatory first comment) in open', () => {
    expect(columnOf(annotation())).toBe('open');
  });

  it('derives "discussion" once the thread grew beyond the first comment', () => {
    expect(columnOf(annotation({ commentCount: 2 }))).toBe('discussion');
  });

  it('places resolved annotations in done regardless of thread size', () => {
    expect(columnOf(annotation({ status: AnnotationStatus.Resolved, commentCount: 5 }))).toBe(
      'done',
    );
    expect(columnOf(annotation({ status: AnnotationStatus.Resolved }))).toBe('done');
  });
});

describe('parseTaskFilter', () => {
  it('accepts the known filters and falls back to all', () => {
    expect(parseTaskFilter('discussion')).toBe('discussion');
    expect(parseTaskFilter('done')).toBe('done');
    expect(parseTaskFilter('bogus')).toBe('all');
    expect(parseTaskFilter(null)).toBe('all');
  });
});

describe('taskTitle', () => {
  it('prefers the opening comment', () => {
    expect(taskTitle(annotation())).toBe('Please extend the payment target');
  });

  it('falls back to the quote, then to a generic label', () => {
    expect(taskTitle(annotation({ firstComment: undefined }))).toBe('the disputed clause');
    expect(taskTitle(annotation({ firstComment: '  ', anchor: undefined }))).toBe('Annotation');
  });
});

describe('taskKeys', () => {
  it('assigns stable T-n keys in creation order regardless of input order', () => {
    const first = annotation({ id: 'b', createdAt: '2026-07-01T09:00:00Z' });
    const second = annotation({ id: 'a', createdAt: '2026-07-01T10:00:00Z' });
    const keys = taskKeys([second, first]);
    expect(keys.get('b')).toBe('T-1');
    expect(keys.get('a')).toBe('T-2');
  });
});

describe('matchesQuery', () => {
  it('searches title, quote and author name case-insensitively', () => {
    expect(matchesQuery(annotation(), 'Maxim', 'PAYMENT')).toBe(true);
    expect(matchesQuery(annotation(), 'Maxim', 'disputed')).toBe(true);
    expect(matchesQuery(annotation(), 'Maxim', 'maxim')).toBe(true);
    expect(matchesQuery(annotation(), 'Maxim', 'liability')).toBe(false);
  });

  it('matches everything on a blank query', () => {
    expect(matchesQuery(annotation(), 'Maxim', '  ')).toBe(true);
  });
});
