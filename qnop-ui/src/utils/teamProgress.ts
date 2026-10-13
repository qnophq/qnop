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
 * Names are `teams` catalog keys (issue #836), translated at render.
 */

import type { ParseKeys } from 'i18next';

/** A key in the `teams` catalog. */
export type TeamsKey = ParseKeys<'teams'>;

export interface TeamTier {
  /** Catalog key of the tier name for the team's current size. */
  nameKey: TeamsKey;
  /** Members at which this tier starts. */
  floor: number;
  /** Members at which the next tier starts, or null at the top tier. */
  nextFloor: number | null;
  /** 0..1 progress from this tier's floor toward the next (1 at the top tier). */
  progress: number;
}

interface TierBand {
  nameKey: TeamsKey;
  floor: number;
}

/** Roster tiers by team size — a small, tasteful ladder, not an XP grind. */
const TIERS: readonly TierBand[] = [
  { nameKey: 'myTeams.tiers.solo', floor: 1 },
  { nameKey: 'myTeams.tiers.squad', floor: 2 },
  { nameKey: 'myTeams.tiers.crew', floor: 5 },
  { nameKey: 'myTeams.tiers.guild', floor: 10 },
  { nameKey: 'myTeams.tiers.chapter', floor: 25 },
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
  return { nameKey: tier.nameKey, floor: tier.floor, nextFloor, progress };
}

/** A leadership rank (its catalog key) from how many teams the caller leads. */
export function leadershipRank(teamsLed: number): TeamsKey {
  if (teamsLed >= 3) return 'myTeams.ranks.programLead';
  if (teamsLed === 2) return 'myTeams.ranks.multiTeamLead';
  if (teamsLed === 1) return 'myTeams.ranks.teamLead';
  return 'myTeams.ranks.member';
}

export interface LeadershipStats {
  teamsLed: number;
  /** Total members across every team the caller leads. */
  totalTeammates: number;
  /** The size of the caller's largest led team. */
  largestTeam: number;
  /** Catalog key of the leadership rank. */
  rankKey: TeamsKey;
}

/** Headline leadership stats derived from the caller's led teams. */
export function leadershipStats(led: readonly { memberCount: number }[]): LeadershipStats {
  const totalTeammates = led.reduce((sum, team) => sum + team.memberCount, 0);
  const largestTeam = led.reduce((max, team) => Math.max(max, team.memberCount), 0);
  return {
    teamsLed: led.length,
    totalTeammates,
    largestTeam,
    rankKey: leadershipRank(led.length),
  };
}

export interface Achievement {
  id: string;
  /** Catalog key of the achievement name. */
  labelKey: TeamsKey;
  /** Catalog key of what earns it — shown when locked, as the next goal to aim for. */
  hintKey: TeamsKey;
  /** Interpolation values for the hint, e.g. the size milestone. */
  hintValues?: Record<string, number>;
  earned: boolean;
}

/** Milestones of the size-based achievements. */
const TEAM_BUILDER_SIZE = 5;
const BIG_ROSTER_SIZE = 10;
const FULL_HOUSE_TEAMMATES = 25;

/** The achievement set, each flagged earned/locked from the caller's led teams. */
export function computeAchievements(led: readonly { memberCount: number }[]): Achievement[] {
  const teamsLed = led.length;
  const largest = led.reduce((max, team) => Math.max(max, team.memberCount), 0);
  const totalTeammates = led.reduce((sum, team) => sum + team.memberCount, 0);
  return [
    {
      id: 'first-team',
      labelKey: 'myTeams.achievements.firstTeam.label',
      hintKey: 'myTeams.achievements.firstTeam.hint',
      earned: teamsLed >= 1,
    },
    {
      id: 'multi-team',
      labelKey: 'myTeams.achievements.multiTeam.label',
      hintKey: 'myTeams.achievements.multiTeam.hint',
      earned: teamsLed >= 2,
    },
    {
      id: 'team-builder',
      labelKey: 'myTeams.achievements.teamBuilder.label',
      hintKey: 'myTeams.achievements.teamBuilder.hint',
      hintValues: { size: TEAM_BUILDER_SIZE },
      earned: largest >= TEAM_BUILDER_SIZE,
    },
    {
      id: 'big-roster',
      labelKey: 'myTeams.achievements.bigRoster.label',
      hintKey: 'myTeams.achievements.bigRoster.hint',
      hintValues: { size: BIG_ROSTER_SIZE },
      earned: largest >= BIG_ROSTER_SIZE,
    },
    {
      id: 'full-house',
      labelKey: 'myTeams.achievements.fullHouse.label',
      hintKey: 'myTeams.achievements.fullHouse.hint',
      hintValues: { size: FULL_HOUSE_TEAMMATES },
      earned: totalTeammates >= FULL_HOUSE_TEAMMATES,
    },
  ];
}
