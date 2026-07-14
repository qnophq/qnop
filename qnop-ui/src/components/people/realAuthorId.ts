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

interface ReviewAnonymityContext {
  /** True for an anonymous review (issue #422). */
  anonymous?: boolean;
  ownerId?: string;
}

/**
 * The anonymity gate for user hover cards inside a review (issues #482,
 * ADR-0038): on the wire, a foreign author's id in an ANONYMOUS review is a
 * synthetic per-document pseudonym token that LOOKS like a user id — calling
 * the profile API with it would 404, and any correlation would defeat
 * anonymity. Returns the author id only when it is guaranteed real: any
 * author in a non-anonymous review, and yourself or the owner (structurally
 * public, issue #472) in an anonymous one. Null means "no card".
 */
export function realAuthorId(
  review: ReviewAnonymityContext | undefined,
  selfId: string | null,
  authorId: string | null | undefined,
): string | null {
  if (!review || !authorId) {
    return null;
  }
  if (!review.anonymous) {
    return authorId;
  }
  return authorId === selfId || authorId === review.ownerId ? authorId : null;
}
