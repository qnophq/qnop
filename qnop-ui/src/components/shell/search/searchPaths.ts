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

import type { DiscussionSearchHit } from '../../../api/generated';

/**
 * The thread deep link of a discussion hit (issue #540): `?annotation=` opens
 * the thread, `&comment=` additionally scrolls-and-pulses a reply (#412).
 */
export function discussionHitPath(hit: DiscussionSearchHit, reply: boolean): string {
  const base = `/reviews/${hit.documentSlug ?? hit.documentId}?annotation=${hit.annotationId}`;
  return reply ? `${base}&comment=${hit.commentId}` : base;
}
