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
package io.qnop.service.image;

/**
 * A bound on the raster an untrusted image may decode to (issues #906, #907): a maximum per side
 * and a maximum total pixel count. The total matters as much as the sides — a long, thin image can
 * stay under any per-side cap and still cost as much memory as a large square.
 *
 * @param maxSide the largest width or height admitted, in pixels
 * @param maxPixels the largest {@code width × height} admitted
 */
public record PixelBudget(int maxSide, long maxPixels) {

  public PixelBudget {
    if (maxSide <= 0 || maxPixels <= 0) {
      throw new IllegalArgumentException("pixel budget must be positive");
    }
  }

  /** True when both sides are positive, neither exceeds the side cap, and the area fits. */
  public boolean admits(ImageHeaderProbe.Dimensions dimensions) {
    int width = dimensions.width();
    int height = dimensions.height();
    return width > 0
        && height > 0
        && width <= maxSide
        && height <= maxSide
        && (long) width * height <= maxPixels;
  }
}
