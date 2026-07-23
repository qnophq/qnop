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

import type { DiscussionSearchHit, GlobalSearchResponse } from '../../../api/generated';

/**
 * The thread deep link of a discussion hit (issue #540): `?annotation=` opens
 * the thread, `&comment=` additionally scrolls-and-pulses a reply (#412).
 */
export function discussionHitPath(hit: DiscussionSearchHit, reply: boolean): string {
  const base = `/reviews/${hit.documentSlug ?? hit.documentId}?annotation=${hit.annotationId}`;
  return reply ? `${base}&comment=${hit.commentId}` : base;
}

/** One keyboard-reachable dropdown row: its stable key and where Enter goes. */
export interface SearchAction {
  key: string;
  path: string;
}

/**
 * The dropdown's navigable rows in render order (issue #540 keyboard roving):
 * every hit row and every "see all" continuation, exactly as painted — locked
 * team rows are not actions, so the arrow keys skip them like a click would.
 */
export function flattenSearchActions(
  data: GlobalSearchResponse,
  query: string,
  selfUserId: string | null,
): SearchAction[] {
  const actions: SearchAction[] = [];
  const seeAll = (type: string) => `/search?q=${encodeURIComponent(query)}&type=${type}`;

  for (const hit of data.reviews.items) {
    actions.push({ key: `review:${hit.id}`, path: `/reviews/${hit.slug ?? hit.id}` });
  }
  if (data.reviews.total > data.reviews.items.length) {
    actions.push({ key: 'seeall:reviews', path: seeAll('reviews') });
  }
  for (const hit of data.annotations.items) {
    actions.push({ key: `annotation:${hit.commentId}`, path: discussionHitPath(hit, false) });
  }
  if (data.annotations.total > data.annotations.items.length) {
    actions.push({ key: 'seeall:annotations', path: seeAll('annotations') });
  }
  for (const hit of data.comments.items) {
    actions.push({ key: `comment:${hit.commentId}`, path: discussionHitPath(hit, true) });
  }
  if (data.comments.total > data.comments.items.length) {
    actions.push({ key: 'seeall:comments', path: seeAll('comments') });
  }
  for (const hit of data.users.items) {
    actions.push({
      key: `user:${hit.userId}`,
      path: hit.userId === selfUserId ? '/profile' : `/users/${hit.slug ?? hit.userId}`,
    });
  }
  if (data.users.total > data.users.items.length) {
    actions.push({ key: 'seeall:users', path: seeAll('users') });
  }
  for (const hit of data.teams.items) {
    if (hit.viewable) {
      actions.push({ key: `team:${hit.teamId}`, path: `/my-teams/${hit.slug ?? hit.teamId}` });
    }
  }
  if (data.teams.total > data.teams.items.length) {
    actions.push({ key: 'seeall:teams', path: seeAll('teams') });
  }
  return actions;
}
