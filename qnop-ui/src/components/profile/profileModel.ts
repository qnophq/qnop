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

import type { DocumentSummary, PublicUserStats } from '../../api/generated';

/** One achievement sticker on the player card (issue #469). */
export interface Achievement {
  key: string;
  title: string;
  /** Earned: what happened. Locked: how to earn it. */
  caption: string;
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
      title: 'Liftoff',
      caption: stats.owned > 0 ? 'Started a review' : 'Start your first review',
      earned: stats.owned > 0,
    },
    {
      key: 'crew',
      title: 'Crew member',
      caption: stats.reviewing > 0 ? 'Joined a review crew' : 'Get invited to a review',
      earned: stats.reviewing > 0,
    },
    {
      key: 'closer',
      title: 'Closer',
      caption:
        stats.completed > 0 ? 'Finalized a review' : 'See one of your reviews through to finalized',
      earned: stats.completed > 0,
    },
    {
      key: 'face',
      title: 'Face on file',
      caption: state.hasAvatar ? 'Teammates recognise you' : 'Upload a profile picture',
      earned: state.hasAvatar,
    },
    {
      key: 'tuned-in',
      title: 'Tuned in',
      caption: state.notificationsOn
        ? 'Review mails keep you posted'
        : 'Switch review notifications on',
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
      title: 'Liftoff',
      caption: stats.reviewsOwned > 0 ? 'Started a review' : 'No review started yet',
      earned: stats.reviewsOwned > 0,
    },
    {
      key: 'crew',
      title: 'Crew member',
      caption: stats.reviewsParticipating > 0 ? 'Joined a review crew' : 'Not on a crew yet',
      earned: stats.reviewsParticipating > 0,
    },
    {
      key: 'closer',
      title: 'Closer',
      caption:
        stats.annotationsResolved > 0 ? 'Resolved a raised concern' : 'No concern settled yet',
      earned: stats.annotationsResolved > 0,
    },
    {
      key: 'voice',
      title: 'Voice',
      caption:
        stats.commentsWritten >= VOICE_MILESTONE
          ? `${VOICE_MILESTONE}+ comments in discussions`
          : `Earned at ${VOICE_MILESTONE} comments`,
      earned: stats.commentsWritten >= VOICE_MILESTONE,
    },
    {
      key: 'sharp-eye',
      title: 'Sharp eye',
      caption:
        stats.annotationsRaised >= SHARP_EYE_MILESTONE
          ? `${SHARP_EYE_MILESTONE}+ annotations raised`
          : `Earned at ${SHARP_EYE_MILESTONE} annotations`,
      earned: stats.annotationsRaised >= SHARP_EYE_MILESTONE,
    },
  ];
}
