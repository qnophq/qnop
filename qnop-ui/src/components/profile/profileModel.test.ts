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
import type { DocumentSummary } from '../../api/generated';
import {
  profileAchievements,
  profileStats,
  publicProfileAchievements,
  profileMoves,
  sharedReviewsWith,
} from './profileModel';

const ME = 'user-me';

const review = (overrides: Partial<DocumentSummary>): DocumentSummary =>
  ({
    id: 'd1',
    title: 'Doc',
    ownerId: ME,
    workflowState: 'IN_REVIEW',
    openAnnotationCount: 0,
    updatedAt: '2026-07-01T10:00:00Z',
    createdAt: '2026-07-01T10:00:00Z',
    ...overrides,
  }) as DocumentSummary;

describe('profileModel (issue #469)', () => {
  it('splits the scoreboard into owned, reviewing and completed', () => {
    const stats = profileStats(
      [
        review({ id: 'a' }),
        review({ id: 'b', workflowState: 'FINALIZED' }),
        review({ id: 'c', ownerId: 'someone-else' }),
      ],
      ME,
    );
    expect(stats).toEqual({ owned: 2, reviewing: 1, completed: 1 });
  });

  it('derives every achievement from real state', () => {
    const achievements = profileAchievements({
      reviews: [review({ workflowState: 'FINALIZED' }), review({ id: 'x', ownerId: 'other' })],
      userId: ME,
      hasAvatar: true,
      notificationsOn: true,
    });
    expect(achievements.every((a) => a.earned)).toBe(true);
  });

  it('locks everything for a brand-new user and captions the way forward', () => {
    const achievements = profileAchievements({
      reviews: [],
      userId: ME,
      hasAvatar: false,
      notificationsOn: false,
    });
    expect(achievements.every((a) => !a.earned)).toBe(true);
    expect(achievements.find((a) => a.key === 'liftoff')?.caption).toBe('Start your first review');
  });
});

describe('publicProfileAchievements (issue #473)', () => {
  const stats = (overrides: Partial<import('../../api/generated').PublicUserStats> = {}) => ({
    reviewsOwned: 0,
    reviewsParticipating: 0,
    annotationsRaised: 0,
    annotationsResolved: 0,
    commentsWritten: 0,
    ...overrides,
  });

  it('locks everything for an inactive user with third-person captions', () => {
    const achievements = publicProfileAchievements(stats());
    expect(achievements.every((a) => !a.earned)).toBe(true);
    expect(achievements.find((a) => a.key === 'liftoff')?.caption).toBe('No review started yet');
  });

  it('applies the milestone thresholds for voice and sharp eye', () => {
    const below = publicProfileAchievements(stats({ commentsWritten: 9, annotationsRaised: 24 }));
    expect(below.find((a) => a.key === 'voice')?.earned).toBe(false);
    expect(below.find((a) => a.key === 'sharp-eye')?.earned).toBe(false);

    const at = publicProfileAchievements(stats({ commentsWritten: 10, annotationsRaised: 25 }));
    expect(at.find((a) => a.key === 'voice')?.earned).toBe(true);
    expect(at.find((a) => a.key === 'sharp-eye')?.earned).toBe(true);
  });

  it('earns the shared badges from server aggregates', () => {
    const achievements = publicProfileAchievements(
      stats({ reviewsOwned: 1, reviewsParticipating: 2, annotationsResolved: 3 }),
    );
    expect(achievements.find((a) => a.key === 'liftoff')?.earned).toBe(true);
    expect(achievements.find((a) => a.key === 'crew')?.earned).toBe(true);
    expect(achievements.find((a) => a.key === 'closer')?.earned).toBe(true);
  });
});

describe('sharedReviewsWith (issue #482 polish)', () => {
  const review = (overrides: Record<string, unknown>) =>
    ({
      id: 'd1',
      title: 'Doc',
      ownerId: 'someone',
      participants: [],
      ...overrides,
    }) as never;

  it('splits the overview into commanded and joined missions', () => {
    const owned = review({ id: 'o1', ownerId: 'anna' });
    const reviewing = review({
      id: 'r1',
      participants: [{ id: 'p1', kind: 'USER', principalId: 'anna', displayName: 'Anna' }],
    });
    const unrelated = review({ id: 'x1' });

    const shared = sharedReviewsWith('anna', [owned, reviewing, unrelated]);

    expect(shared.owned.map((r: { id: string }) => r.id)).toEqual(['o1']);
    expect(shared.reviewing.map((r: { id: string }) => r.id)).toEqual(['r1']);
  });

  it('never counts teams or an owned review as joined', () => {
    const ownAndParticipant = review({
      id: 'o1',
      ownerId: 'anna',
      participants: [{ id: 'p1', kind: 'USER', principalId: 'anna', displayName: 'Anna' }],
    });
    const teamOnly = review({
      id: 't1',
      participants: [{ id: 'p2', kind: 'TEAM', principalId: 'anna', displayName: 'Team' }],
    });

    const shared = sharedReviewsWith('anna', [ownAndParticipant, teamOnly]);

    expect(shared.owned.map((r: { id: string }) => r.id)).toEqual(['o1']);
    expect(shared.reviewing).toHaveLength(0);
  });
});

describe('profileMoves (issue #482 polish)', () => {
  it('keeps only the profiled actor’s feed items', () => {
    const move = (actorId: string | undefined, type: string) =>
      ({ actorId, type, documentId: 'd', documentTitle: 'Doc', createdAt: 'now' }) as never;

    const moves = profileMoves('anna', [
      move('anna', 'annotation.created'),
      move('ben', 'annotation.resolved'),
      move(undefined, 'workflow.transition'), // anonymised actors never match
    ]);

    expect(moves).toHaveLength(1);
    expect((moves[0] as { type: string }).type).toBe('annotation.created');
  });
});
