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

import type { ParseKeys } from 'i18next';
import type { DashboardActivity, DocumentSummary, PublicUserStats } from '../../api/generated';

/** A key in the `profile` catalog (issue #464) — the model names text, the view translates it. */
export type ProfileTextKey = ParseKeys<'profile'>;

/** One achievement sticker on the player card (issue #469). */
export interface Achievement {
  key: string;
  /** Catalog key of the badge name. */
  titleKey: ProfileTextKey;
  /** Catalog key of the caption. Earned: what happened. Locked: how to earn it. */
  captionKey: ProfileTextKey;
  /** Interpolation values for the caption, e.g. a milestone. */
  captionValues?: Record<string, number | string>;
  earned: boolean;
}

/** The little scoreboard on the identity card. */
export interface ProfileStats {
  owned: number;
  reviewing: number;
  completed: number;
}

const FINALIZED = 'FINALIZED';

export function profileStats(reviews: DocumentSummary[], userId: string | null): ProfileStats {
  const owned = reviews.filter((review) => review.ownerId === userId);
  return {
    owned: owned.length,
    reviewing: reviews.filter((review) => review.ownerId !== userId).length,
    completed: owned.filter((review) => review.workflowState === FINALIZED).length,
  };
}

/**
 * The profile's achievement stickers, every one derived from real state — no
 * separate progression store, so they can never disagree with the data.
 */
export function profileAchievements(state: {
  reviews: DocumentSummary[];
  userId: string | null;
  hasAvatar: boolean;
  notificationsOn: boolean;
}): Achievement[] {
  const stats = profileStats(state.reviews, state.userId);
  return [
    {
      key: 'liftoff',
      titleKey: 'achievements.liftoff.title',
      captionKey: stats.owned > 0 ? 'achievements.liftoff.earned' : 'achievements.liftoff.locked',
      earned: stats.owned > 0,
    },
    {
      key: 'crew',
      titleKey: 'achievements.crew.title',
      captionKey: stats.reviewing > 0 ? 'achievements.crew.earned' : 'achievements.crew.locked',
      earned: stats.reviewing > 0,
    },
    {
      key: 'closer',
      titleKey: 'achievements.closer.title',
      captionKey: stats.completed > 0 ? 'achievements.closer.earned' : 'achievements.closer.locked',
      earned: stats.completed > 0,
    },
    {
      key: 'face',
      titleKey: 'achievements.face.title',
      captionKey: state.hasAvatar ? 'achievements.face.earned' : 'achievements.face.locked',
      earned: state.hasAvatar,
    },
    {
      key: 'tuned-in',
      titleKey: 'achievements.tunedIn.title',
      captionKey: state.notificationsOn
        ? 'achievements.tunedIn.earned'
        : 'achievements.tunedIn.locked',
      earned: state.notificationsOn,
    },
  ];
}

/** Milestones for the voice / sharp-eye badges (issue #473). */
const VOICE_MILESTONE = 10;
const SHARP_EYE_MILESTONE = 25;

/**
 * The PUBLIC profile's achievements (issue #473), derived from the server's
 * contribution aggregates — captions read in the third person, and the
 * private badges (avatar, notifications) stay off a colleague's page.
 */
export function publicProfileAchievements(stats: PublicUserStats): Achievement[] {
  return [
    {
      key: 'liftoff',
      titleKey: 'achievements.liftoff.title',
      captionKey:
        stats.reviewsOwned > 0
          ? 'achievements.liftoff.earned'
          : 'achievements.liftoff.lockedPublic',
      earned: stats.reviewsOwned > 0,
    },
    {
      key: 'crew',
      titleKey: 'achievements.crew.title',
      captionKey:
        stats.reviewsParticipating > 0
          ? 'achievements.crew.earned'
          : 'achievements.crew.lockedPublic',
      earned: stats.reviewsParticipating > 0,
    },
    {
      key: 'closer',
      titleKey: 'achievements.closer.title',
      captionKey:
        stats.annotationsResolved > 0
          ? 'achievements.closer.earnedPublic'
          : 'achievements.closer.lockedPublic',
      earned: stats.annotationsResolved > 0,
    },
    {
      key: 'voice',
      titleKey: 'achievements.voice.title',
      captionKey:
        stats.commentsWritten >= VOICE_MILESTONE
          ? 'achievements.voice.earned'
          : 'achievements.voice.locked',
      captionValues: { milestone: VOICE_MILESTONE },
      earned: stats.commentsWritten >= VOICE_MILESTONE,
    },
    {
      key: 'sharp-eye',
      titleKey: 'achievements.sharpEye.title',
      captionKey:
        stats.annotationsRaised >= SHARP_EYE_MILESTONE
          ? 'achievements.sharpEye.earned'
          : 'achievements.sharpEye.locked',
      captionValues: { milestone: SHARP_EYE_MILESTONE },
      earned: stats.annotationsRaised >= SHARP_EYE_MILESTONE,
    },
  ];
}

/** The viewer-visible reviews split by the profiled person's role (issue #482 polish). */
export interface SharedReviews {
  /** Reviews the person owns — ownership is structurally public (#472). */
  owned: DocumentSummary[];
  /** Reviews the person reviews. Anonymised rosters carry synthetic ids and never match. */
  reviewing: DocumentSummary[];
}

/**
 * Splits the VIEWER's reviews overview by the profiled person's role. The
 * overview is already visibility-scoped and anonymity-safe server-side, so
 * this only ever surfaces what the viewer could open anyway.
 */
export function sharedReviewsWith(profileId: string, reviews: DocumentSummary[]): SharedReviews {
  return {
    owned: reviews.filter((review) => review.ownerId === profileId),
    reviewing: reviews.filter(
      (review) =>
        review.ownerId !== profileId &&
        (review.participants ?? []).some(
          (participant) => participant.kind === 'USER' && participant.principalId === profileId,
        ),
    ),
  };
}

/**
 * The person's recent moves as seen by the viewer (issue #482 polish): the
 * dashboard feed filtered to this actor. The feed is scoped to the viewer's
 * reviews and pseudonymises anonymous ones server-side (a token never equals
 * a real id), so nothing appears here that the viewer may not attribute.
 */
export function profileMoves(
  profileId: string,
  activity: DashboardActivity[],
): DashboardActivity[] {
  return activity.filter((item) => item.actorId === profileId);
}
