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
 * The public avatar read path for a user id (ADR-0031). Works for every id the
 * server exposes: a real user renders their picture, a user without one — or a
 * pseudonymised token in an anonymous review (#413) — answers 404 and the
 * avatar quietly falls back to initials.
 */
export function avatarSrc(userId: string | null | undefined): string | null {
  return userId ? `/api/v1/users/${userId}/avatar` : null;
}

/**
 * The public avatar read path for a team id (issue #509). Like {@link avatarSrc}: a team without a
 * picture answers 404 and {@code TeamAvatar} falls back to its initials crest. Used where only the
 * team id is known (the committed review participants) rather than a DTO-carried avatar URL.
 */
export function teamAvatarSrc(teamId: string | null | undefined): string | null {
  return teamId ? `/api/v1/teams/${teamId}/avatar` : null;
}
