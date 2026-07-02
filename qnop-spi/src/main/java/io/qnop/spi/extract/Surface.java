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

import java.util.List;

/**
 * One renderable plane of a document (ADR-0032): a PDF page, an image plane, or a converted DOCX
 * page. {@code width}/{@code height} are the intrinsic size in the source's native units (PDF
 * points, image pixels); all {@link NormalizedBox} coordinates are fractions of them. The text
 * layer is optional — an image is simply a surface with no spans.
 *
 * @param index zero-based surface index within the document
 * @param width intrinsic width, {@code > 0}
 * @param height intrinsic height, {@code > 0}
 * @param textSpans ordered text runs; empty (never null) when the surface has no text layer
 */
public record Surface(int index, double width, double height, List<TextSpan> textSpans) {

  public Surface {
    if (index < 0) {
      throw new IllegalArgumentException("index must be >= 0");
    }
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("surface size must be positive");
    }
    textSpans = textSpans == null ? List.of() : List.copyOf(textSpans);
  }
}
