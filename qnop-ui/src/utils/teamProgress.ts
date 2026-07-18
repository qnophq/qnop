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

/**
 * Light, honest "gamification" for the My Teams surface (issue #470): purely
 * derived views over the real team data (member counts, teams led) — a roster
 * tier per team, a leadership rank, and a set of achievements with progress. No
 * invented metrics; every number traces back to the API. Kept as pure functions
 * so the motivational layer is unit-tested independently of the React tree.
 */

export interface TeamTier {
  /** The tier name for the team's current size. */
  name: string;
  /** Members at which this tier starts. */
  floor: number;
  /** Members at which the next tier starts, or null at the top tier. */
  nextFloor: number | null;
  /** 0..1 progress from this tier's floor toward the next (1 at the top tier). */
  progress: number;
}

interface TierBand {
  name: string;
  floor: number;
}

/** Roster tiers by team size — a small, tasteful ladder, not an XP grind. */
const TIERS: readonly TierBand[] = [
  { name: 'Solo', floor: 1 },
  { name: 'Squad', floor: 2 },
  { name: 'Crew', floor: 5 },
  { name: 'Guild', floor: 10 },
  { name: 'Chapter', floor: 25 },
];

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

/** The roster tier for a team of {@code memberCount} people, with progress to the next. */
export function teamTier(memberCount: number): TeamTier {
  const count = Math.max(0, Math.floor(memberCount));
  let index = 0;
  for (let i = 0; i < TIERS.length; i += 1) {
    if (count >= TIERS[i].floor) index = i;
  }
  const tier = TIERS[index];
  const next = TIERS[index + 1] ?? null;
  const nextFloor = next ? next.floor : null;
  const progress =
    nextFloor === null ? 1 : clamp01((count - tier.floor) / (nextFloor - tier.floor));
  return { name: tier.name, floor: tier.floor, nextFloor, progress };
}

/** A leadership rank from how many teams the caller leads. */
export function leadershipRank(teamsLed: number): string {
  if (teamsLed >= 3) return 'Program Lead';
  if (teamsLed === 2) return 'Multi-team Lead';
  if (teamsLed === 1) return 'Team Lead';
  return 'Member';
}

export interface LeadershipStats {
  teamsLed: number;
  /** Total members across every team the caller leads. */
  totalTeammates: number;
  /** The size of the caller's largest led team. */
  largestTeam: number;
  rank: string;
}

/** Headline leadership stats derived from the caller's led teams. */
export function leadershipStats(led: readonly { memberCount: number }[]): LeadershipStats {
  const totalTeammates = led.reduce((sum, team) => sum + team.memberCount, 0);
  const largestTeam = led.reduce((max, team) => Math.max(max, team.memberCount), 0);
  return {
    teamsLed: led.length,
    totalTeammates,
    largestTeam,
    rank: leadershipRank(led.length),
  };
}

export interface Achievement {
  id: string;
  label: string;
  /** What earns it — shown when locked, as the next goal to aim for. */
  hint: string;
  earned: boolean;
}

/** The achievement set, each flagged earned/locked from the caller's led teams. */
export function computeAchievements(led: readonly { memberCount: number }[]): Achievement[] {
  const teamsLed = led.length;
  const largest = led.reduce((max, team) => Math.max(max, team.memberCount), 0);
  const totalTeammates = led.reduce((sum, team) => sum + team.memberCount, 0);
  return [
    {
      id: 'first-team',
      label: 'First team',
      hint: 'Lead your first team',
      earned: teamsLed >= 1,
    },
    {
      id: 'multi-team',
      label: 'Multi-team lead',
      hint: 'Lead two or more teams',
      earned: teamsLed >= 2,
    },
    {
      id: 'team-builder',
      label: 'Team builder',
      hint: 'Grow a team to 5 members',
      earned: largest >= 5,
    },
    {
      id: 'big-roster',
      label: 'Big roster',
      hint: 'Grow a team to 10 members',
      earned: largest >= 10,
    },
    {
      id: 'full-house',
      label: 'Full house',
      hint: 'Lead 25 teammates in total',
      earned: totalTeammates >= 25,
    },
  ];
}
