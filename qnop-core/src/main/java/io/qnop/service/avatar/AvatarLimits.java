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
package io.qnop.service.avatar;

import java.util.Set;

/**
 * Bounds for profile-avatar uploads (issue #117). Avatars are small, one-per-user images (ADR-0031)
 * with a tight cap. The allowed content types match the {@code user_avatar} {@code CHECK} domain
 * (migration 0009). SVG is deliberately excluded — an SVG avatar is an XSS surface even sanitized,
 * and the client always uploads a rasterized square crop.
 */
public final class AvatarLimits {

  private AvatarLimits() {}

  public static final String PNG = "image/png";
  public static final String JPEG = "image/jpeg";
  public static final String WEBP = "image/webp";

  /** Content types accepted on upload, derived from the bytes (not the client-declared header). */
  public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(PNG, JPEG, WEBP);

  /** Maximum stored size in bytes (1 MiB) — an avatar is a small square, not a document. */
  public static final long MAX_SIZE_BYTES = 1024L * 1024L;

  /**
   * Maximum raster width/height in pixels; the client crops to a canonical square well under this.
   */
  public static final int MAX_DIMENSION_PX = 1024;
}
