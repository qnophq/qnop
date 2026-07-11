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

import { beforeEach, describe, expect, it } from 'vitest';
import type { DocumentSummary } from '../../api/generated';
import {
  activityPhrase,
  deadlines,
  dueUrgency,
  greeting,
  myReviews,
  readyToFinalize,
  reviewPath,
  waitingOnYou,
} from './dashboardModel';
import { readRecentReviews, recordRecentReview } from './recentReviews';

const ME = 'me';

function review(overrides: Partial<DocumentSummary> = {}): DocumentSummary {
  return {
    id: 'd1',
    title: 'Master agreement',
    ownerId: 'someone-else',
    workflowState: 'IN_REVIEW',
    latestVersionNumber: 1,
    annotationCount: 4,
    openAnnotationCount: 2,
    participants: [],
    createdAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-01T10:00:00Z',
    ...overrides,
  } as DocumentSummary;
}

describe('dueUrgency (issue #454)', () => {
  const now = new Date('2026-07-11T12:00:00Z');

  it('flags overdue with the day count', () => {
    expect(dueUrgency('2026-07-08T12:00:00Z', now)).toEqual({
      level: 'overdue',
      label: 'Overdue by 3 days',
    });
  });

  it('reads a same-day deadline as due today', () => {
    expect(dueUrgency('2026-07-11T13:00:00Z', now).level).toBe('today');
  });

  it('counts down nearby deadlines as soon, distant ones as later', () => {
    expect(dueUrgency('2026-07-12T12:00:00Z', now)).toEqual({
      level: 'soon',
      label: 'Due tomorrow',
    });
    expect(dueUrgency('2026-07-14T12:00:00Z', now).level).toBe('soon');
    expect(dueUrgency('2026-07-25T12:00:00Z', now)).toEqual({
      level: 'later',
      label: '14 days left',
    });
  });
});

describe('role split (issue #454)', () => {
  it('waitingOnYou keeps only open foreign reviews with open annotations', () => {
    const reviews = [
      review({ id: 'w1' }), // reviewer, open work → waiting
      review({ id: 'o1', ownerId: ME }), // owned → not waiting
      review({ id: 'f1', workflowState: 'FINALIZED' }), // closed → not waiting
      review({ id: 'z1', openAnnotationCount: 0 }), // nothing open → not waiting
    ];
    expect(waitingOnYou(reviews, ME).map((r) => r.id)).toEqual(['w1']);
  });

  it('sorts the waiting list by due date first, then latest activity', () => {
    const reviews = [
      review({ id: 'later', dueAt: '2026-08-01T00:00:00Z' }),
      review({ id: 'none', updatedAt: '2026-07-10T00:00:00Z' }),
      review({ id: 'soon', dueAt: '2026-07-12T00:00:00Z' }),
    ];
    expect(waitingOnYou(reviews, ME).map((r) => r.id)).toEqual(['soon', 'later', 'none']);
  });

  it('myReviews lists owned reviews, running ones first', () => {
    const reviews = [
      review({
        id: 'closed',
        ownerId: ME,
        workflowState: 'FINALIZED',
        updatedAt: '2026-07-09T00:00:00Z',
      }),
      review({ id: 'open', ownerId: ME, updatedAt: '2026-07-01T00:00:00Z' }),
      review({ id: 'foreign' }),
    ];
    expect(myReviews(reviews, ME).map((r) => r.id)).toEqual(['open', 'closed']);
  });

  it('deadlines keeps open reviews with a due date, soonest first', () => {
    const reviews = [
      review({ id: 'b', dueAt: '2026-08-01T00:00:00Z' }),
      review({ id: 'a', dueAt: '2026-07-12T00:00:00Z' }),
      review({ id: 'done', dueAt: '2026-07-01T00:00:00Z', workflowState: 'FINALIZED' }),
      review({ id: 'undated' }),
    ];
    expect(deadlines(reviews).map((r) => r.id)).toEqual(['a', 'b']);
  });

  it('readyToFinalize needs annotations, all settled, on a running review', () => {
    expect(readyToFinalize(review({ annotationCount: 3, openAnnotationCount: 0 }))).toBe(true);
    expect(readyToFinalize(review({ annotationCount: 0, openAnnotationCount: 0 }))).toBe(false);
    expect(readyToFinalize(review({ openAnnotationCount: 1 }))).toBe(false);
    expect(
      readyToFinalize(
        review({ annotationCount: 3, openAnnotationCount: 0, workflowState: 'FINALIZED' }),
      ),
    ).toBe(false);
  });
});

describe('labels & paths', () => {
  it('greets by the hour, as the prototype does', () => {
    expect(greeting(8)).toBe('Good morning');
    expect(greeting(14)).toBe('Good afternoon');
    expect(greeting(21)).toBe('Good evening');
  });

  it('prefers the slug in review paths', () => {
    expect(reviewPath({ id: 'uuid', slug: 'master-agreement' })).toBe('/reviews/master-agreement');
    expect(reviewPath({ id: 'uuid', slug: null })).toBe('/reviews/uuid');
  });

  it('maps every audit type to a verb phrase', () => {
    expect(activityPhrase('annotation.resolved')).toBe('resolved an annotation in');
    expect(activityPhrase('something.else')).toBe('updated');
  });
});

describe('recent reviews (device-local continue strip)', () => {
  beforeEach(() => localStorage.clear());

  it('round-trips, deduplicates by id and caps at four', () => {
    for (const n of [1, 2, 3, 4, 5]) {
      recordRecentReview({ id: `d${n}`, slug: null, title: `Doc ${n}` });
    }
    recordRecentReview({ id: 'd3', slug: null, title: 'Doc 3 again' });

    const recents = readRecentReviews();
    expect(recents.map((r) => r.id)).toEqual(['d3', 'd5', 'd4', 'd2']);
    expect(recents[0].title).toBe('Doc 3 again');
  });

  it('survives corrupted storage', () => {
    localStorage.setItem('qnop-recent-reviews', '{not json');
    expect(readRecentReviews()).toEqual([]);
  });
});
