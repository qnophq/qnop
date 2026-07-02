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
package io.qnop.spi.extract;

/**
 * An axis-aligned rectangle normalized to the {@code 0..1} range of its {@link Surface} (ADR-0032):
 * {@code x}/{@code y} is the top-left corner, the origin is the surface's top-left, and all four
 * values are fractions of the surface's intrinsic width/height. Normalization makes a highlight box
 * sit correctly regardless of the client's zoom or DPI.
 */
public record NormalizedBox(double x, double y, double width, double height) {

  public NormalizedBox {
    if (!inUnitRange(x) || !inUnitRange(y) || !inUnitRange(width) || !inUnitRange(height)) {
      throw new IllegalArgumentException(
          "box values must be within 0..1: x=%s y=%s width=%s height=%s"
              .formatted(x, y, width, height));
    }
  }

  private static boolean inUnitRange(double value) {
    return value >= 0.0d && value <= 1.0d;
  }
}
