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
 * One extracted text run on a {@link Surface} (ADR-0032): the text itself, its character-offset
 * range within the surface's canonical text (spans joined by a single {@code \n}), and the
 * normalized bounding box covering its glyphs. Offsets give annotations a text-quote anchor; the
 * box gives them geometry (ADR-0009).
 *
 * <p>{@code charAdvances} optionally carries glyph-true sub-line geometry (issue #290): the
 * normalized x of each character's <em>right edge</em> on the surface, in the same coordinate space
 * as {@code box}. Character {@code i} spans from {@code i == 0 ? box.x() : charAdvances.get(i - 1)}
 * to {@code charAdvances.get(i)}. Values are non-decreasing and the list length equals the text
 * length. Extractors that cannot attribute per-character geometry emit {@code null}; consumers then
 * distribute characters uniformly across the box.
 *
 * @param text the run's text, never blank
 * @param startOffset inclusive start within the surface's canonical text
 * @param endOffset exclusive end ({@code startOffset + text.length()})
 * @param box normalized glyph bounding box on the surface
 * @param charAdvances per-character right-edge x positions, or null for uniform fallback
 */
public record TextSpan(
    String text, int startOffset, int endOffset, NormalizedBox box, List<Double> charAdvances) {

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
    if (charAdvances != null) {
      if (charAdvances.size() != text.length()) {
        throw new IllegalArgumentException(
            "charAdvances length %d does not match text length %d"
                .formatted(charAdvances.size(), text.length()));
      }
      charAdvances = List.copyOf(charAdvances);
    }
  }

  /** A span without per-character geometry — consumers fall back to uniform distribution. */
  public TextSpan(String text, int startOffset, int endOffset, NormalizedBox box) {
    this(text, startOffset, endOffset, box, null);
  }
}
