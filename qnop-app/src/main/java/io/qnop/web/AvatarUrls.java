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
package io.qnop.web;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the public avatar URL exposed on user DTOs (issue #117). The {@code ?v=} cache-buster is
 * the avatar's {@code updated_at} epoch, so the browser refetches whenever the picture changes
 * while still caching it (the read path also serves an ETag). Returns {@code null} when the user
 * has no avatar, so the SPA renders the initials fallback.
 */
final class AvatarUrls {

  private AvatarUrls() {}

  static String forUser(UUID userId, Instant updatedAt) {
    if (updatedAt == null) {
      return null;
    }
    return ApiPathConfig.API_V1_PREFIX
        + "/users/"
        + userId
        + "/avatar?v="
        + updatedAt.toEpochMilli();
  }

  /** The team counterpart (issue #509); {@code null} when the team has no avatar. */
  static String forTeam(UUID teamId, Instant updatedAt) {
    if (updatedAt == null) {
      return null;
    }
    return ApiPathConfig.API_V1_PREFIX
        + "/teams/"
        + teamId
        + "/avatar?v="
        + updatedAt.toEpochMilli();
  }
}
