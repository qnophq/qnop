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
import { computeAchievements, leadershipRank, leadershipStats, teamTier } from './teamProgress';

describe('teamTier', () => {
  it('names the tier for the team size', () => {
    expect(teamTier(1).name).toBe('Solo');
    expect(teamTier(3).name).toBe('Squad');
    expect(teamTier(7).name).toBe('Crew');
    expect(teamTier(12).name).toBe('Guild');
    expect(teamTier(40).name).toBe('Chapter');
  });

  it('reports progress toward the next tier', () => {
    // Squad spans 2..4; 3 is halfway to Crew (5).
    const squad = teamTier(3);
    expect(squad.nextFloor).toBe(5);
    expect(squad.progress).toBeCloseTo((3 - 2) / (5 - 2));
  });

  it('caps progress at the top tier', () => {
    const top = teamTier(100);
    expect(top.nextFloor).toBeNull();
    expect(top.progress).toBe(1);
  });

  it('is robust to zero and fractional counts', () => {
    expect(teamTier(0).name).toBe('Solo');
    expect(teamTier(0).progress).toBe(0);
    expect(teamTier(4.9).name).toBe('Squad');
  });
});

describe('leadershipRank', () => {
  it('scales with the number of teams led', () => {
    expect(leadershipRank(0)).toBe('Member');
    expect(leadershipRank(1)).toBe('Team Lead');
    expect(leadershipRank(2)).toBe('Multi-team Lead');
    expect(leadershipRank(5)).toBe('Program Lead');
  });
});

describe('leadershipStats', () => {
  it('sums teammates, finds the largest team and the rank', () => {
    const stats = leadershipStats([{ memberCount: 4 }, { memberCount: 9 }]);
    expect(stats.teamsLed).toBe(2);
    expect(stats.totalTeammates).toBe(13);
    expect(stats.largestTeam).toBe(9);
    expect(stats.rank).toBe('Multi-team Lead');
  });

  it('handles leading no team', () => {
    const stats = leadershipStats([]);
    expect(stats).toEqual({ teamsLed: 0, totalTeammates: 0, largestTeam: 0, rank: 'Member' });
  });
});

describe('computeAchievements', () => {
  it('unlocks by milestones and leaves the rest as goals', () => {
    const earned = (led: { memberCount: number }[]) =>
      computeAchievements(led)
        .filter((a) => a.earned)
        .map((a) => a.id);

    expect(earned([])).toEqual([]);
    expect(earned([{ memberCount: 1 }])).toEqual(['first-team']);
    expect(earned([{ memberCount: 6 }, { memberCount: 2 }])).toEqual([
      'first-team',
      'multi-team',
      'team-builder',
    ]);
    expect(earned([{ memberCount: 12 }, { memberCount: 14 }])).toContain('big-roster');
    expect(earned([{ memberCount: 12 }, { memberCount: 14 }])).toContain('full-house');
  });

  it('always returns the full ladder with hints for locked goals', () => {
    const all = computeAchievements([]);
    expect(all).toHaveLength(5);
    expect(all.every((a) => a.hint.length > 0)).toBe(true);
  });
});
