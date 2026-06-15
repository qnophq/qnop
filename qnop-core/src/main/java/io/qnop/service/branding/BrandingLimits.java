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
package io.qnop.service.branding;

import java.util.Set;

/**
 * Bounds for branding uploads (issue #23). Branding assets are small, config-like blobs (ADR-0024),
 * so the caps are deliberately tight. The allowed content types match the {@code application_asset}
 * {@code CHECK} domain (migration 0005).
 */
public final class BrandingLimits {

  private BrandingLimits() {}

  public static final String PNG = "image/png";
  public static final String WEBP = "image/webp";
  public static final String SVG = "image/svg+xml";

  /** Content types accepted on upload, derived from the bytes (not the client-declared header). */
  public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(PNG, WEBP, SVG);

  /** Maximum stored size in bytes (512 KiB) — branding is a small logo, not a document. */
  public static final long MAX_SIZE_BYTES = 512L * 1024L;

  /** Maximum raster width/height in pixels; logos beyond this are almost certainly a mistake. */
  public static final int MAX_DIMENSION_PX = 2000;
}
