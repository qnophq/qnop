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
import { profileAchievements, profileStats } from './profileModel';

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
