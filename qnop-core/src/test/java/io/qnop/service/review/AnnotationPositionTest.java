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
package io.qnop.service.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reading order the export sorts by (issue #547). Pure parsing and comparison — exactly the
 * kind of logic that must stay testable without a database (CLAUDE.md guardrail), because the
 * anchor it reads is opaque {@code jsonb} the rest of the system never interprets.
 */
class AnnotationPositionTest {

  private static String anchor(int surface, double x, double y) {
    return "{\"region\":{\"surfaceIndex\":"
        + surface
        + ",\"box\":{\"x\":"
        + x
        + ",\"y\":"
        + y
        + ",\"width\":0.2,\"height\":0.05}}}";
  }

  @Test
  @DisplayName("a region anchor yields its page and coordinates")
  void parsesRegion() {
    AnnotationPosition position = AnnotationPosition.parse(anchor(2, 0.4, 0.75));

    assertThat(position.placed()).isTrue();
    assertThat(position.surfaceIndex()).isEqualTo(2);
    assertThat(position.pageNumber()).isEqualTo(3); // surfaceIndex is 0-based
    assertThat(position.x()).isEqualTo(0.4);
    assertThat(position.y()).isEqualTo(0.75);
  }

  @Test
  @DisplayName("the text layer's offset is picked up when present")
  void parsesTextPosition() {
    String withText =
        "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.1,\"y\":0.1}},"
            + "\"textPosition\":{\"start\":420,\"end\":460}}";

    assertThat(AnnotationPosition.parse(withText).textStart()).isEqualTo(420);
  }

  @Test
  @DisplayName("anything unreadable is unplaced rather than fatal")
  void unreadableAnchorsAreUnplaced() {
    // One odd anchor must never cost the whole export. The format is open by
    // design (ADR-0009), so an unknown shape is expected, not exceptional.
    assertThat(AnnotationPosition.parse(null)).isEqualTo(AnnotationPosition.UNPLACED);
    assertThat(AnnotationPosition.parse("")).isEqualTo(AnnotationPosition.UNPLACED);
    assertThat(AnnotationPosition.parse("not json at all")).isEqualTo(AnnotationPosition.UNPLACED);
    assertThat(AnnotationPosition.parse("{}")).isEqualTo(AnnotationPosition.UNPLACED);
    // A document-scoped annotation (issue #395) legitimately has no region.
    assertThat(AnnotationPosition.parse("{\"scope\":\"document\"}"))
        .isEqualTo(AnnotationPosition.UNPLACED);
    // A shape that looks like an anchor but is not one.
    assertThat(AnnotationPosition.parse("{\"region\":{\"surfaceIndex\":\"two\"}}"))
        .isEqualTo(AnnotationPosition.UNPLACED);
  }

  @Test
  @DisplayName("reading order is page, then top to bottom, then left to right")
  void readingOrder() {
    List<AnnotationPosition> sorted =
        Stream.of(
                AnnotationPosition.parse(anchor(1, 0.1, 0.2)), // page 2, high
                AnnotationPosition.parse(anchor(0, 0.8, 0.5)), // page 1, right
                AnnotationPosition.parse(anchor(0, 0.1, 0.5)), // page 1, left, same line
                AnnotationPosition.parse(anchor(0, 0.5, 0.1))) // page 1, top
            .sorted(AnnotationPosition.READING_ORDER)
            .toList();

    assertThat(sorted)
        .extracting(AnnotationPosition::surfaceIndex, AnnotationPosition::y, AnnotationPosition::x)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(0, 0.1, 0.5),
            org.assertj.core.groups.Tuple.tuple(0, 0.5, 0.1),
            org.assertj.core.groups.Tuple.tuple(0, 0.5, 0.8),
            org.assertj.core.groups.Tuple.tuple(1, 0.2, 0.1));
  }

  @Test
  @DisplayName("unplaced annotations sort last, whatever their page number would be")
  void unplacedSortLast() {
    List<AnnotationPosition> sorted =
        Stream.of(
                AnnotationPosition.UNPLACED,
                AnnotationPosition.parse(anchor(9, 0.9, 0.9)),
                AnnotationPosition.UNPLACED,
                AnnotationPosition.parse(anchor(0, 0.1, 0.1)))
            .sorted(AnnotationPosition.READING_ORDER)
            .toList();

    assertThat(sorted)
        .extracting(AnnotationPosition::placed)
        .containsExactly(true, true, false, false);
  }
}
