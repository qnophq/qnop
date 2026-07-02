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
 * One extracted text run on a {@link Surface} (ADR-0032): the text itself, its character-offset
 * range within the surface's canonical text (spans joined by a single {@code \n}), and the
 * normalized bounding box covering its glyphs. Offsets give annotations a text-quote anchor; the
 * box gives them geometry (ADR-0009).
 *
 * @param text the run's text, never blank
 * @param startOffset inclusive start within the surface's canonical text
 * @param endOffset exclusive end ({@code startOffset + text.length()})
 * @param box normalized glyph bounding box on the surface
 */
public record TextSpan(String text, int startOffset, int endOffset, NormalizedBox box) {

  public TextSpan {
    if (text == null || text.isEmpty()) {
      throw new IllegalArgumentException("text must not be empty");
    }
    if (startOffset < 0 || endOffset != startOffset + text.length()) {
      throw new IllegalArgumentException(
          "offset range [%d,%d) does not match text length %d"
              .formatted(startOffset, endOffset, text.length()));
    }
    if (box == null) {
      throw new IllegalArgumentException("box is required");
    }
  }
}
