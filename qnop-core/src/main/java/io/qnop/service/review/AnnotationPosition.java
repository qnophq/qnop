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

import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where an annotation sits in the document (issue #547) — the reading order a person would follow.
 *
 * <p>Annotations are deliberately position-free (ADR-0009): identity and status are
 * version-independent, and the physical location lives in the placement's opaque {@code jsonb}
 * anchor. So there is nothing to {@code ORDER BY} in SQL; a document order has to be parsed out of
 * that anchor and applied in memory.
 *
 * <p>The parsing is deliberately forgiving. The anchor format is open by design — a region-only
 * image anchor carries no text layer, and future formats may add fields — so anything unreadable
 * yields {@link #UNPLACED} rather than an exception. An export must not fail because one annotation
 * has an anchor shape this code did not expect.
 */
public record AnnotationPosition(
    boolean placed, int surfaceIndex, double y, double x, int textStart) {

  private static final Logger log = LoggerFactory.getLogger(AnnotationPosition.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** Anchor-less or unreadable: sorts after everything that has a place on a page. */
  public static final AnnotationPosition UNPLACED =
      new AnnotationPosition(false, Integer.MAX_VALUE, 0, 0, 0);

  /**
   * Reading order: page, then top to bottom, then left to right, then — where a text layer exists —
   * the character offset, which disambiguates two annotations sharing a line.
   *
   * <p>It is deliberately NOT a total order on its own: two annotations can occupy the same spot.
   * Callers append a stable tiebreaker (creation time, then id) so the export of an unchanged
   * review is byte-identical run over run.
   */
  public static final Comparator<AnnotationPosition> READING_ORDER =
      Comparator.comparing(AnnotationPosition::placed, Comparator.reverseOrder())
          .thenComparingInt(AnnotationPosition::surfaceIndex)
          .thenComparingDouble(AnnotationPosition::y)
          .thenComparingDouble(AnnotationPosition::x)
          .thenComparingInt(AnnotationPosition::textStart);

  /**
   * Reads the position out of an anchor, or {@link #UNPLACED} when there is none to read.
   *
   * @param anchorJson the placement's raw anchor, as {@code AnnotationView} carries it
   */
  public static AnnotationPosition parse(String anchorJson) {
    if (anchorJson == null || anchorJson.isBlank()) {
      return UNPLACED;
    }
    try {
      JsonNode anchor = MAPPER.readTree(anchorJson);
      JsonNode region = anchor.path("region");
      JsonNode surface = region.path("surfaceIndex");
      if (!surface.isNumber()) {
        // A document-scoped annotation (issue #395) has no region at all.
        return UNPLACED;
      }
      JsonNode box = region.path("box");
      return new AnnotationPosition(
          true,
          surface.asInt(),
          box.path("y").asDouble(0),
          box.path("x").asDouble(0),
          anchor.path("textPosition").path("start").asInt(0));
      // Jackson 3's JacksonException IS a RuntimeException, so one catch covers both
      // a malformed document and an unexpected shape.
    } catch (RuntimeException e) {
      // Never fatal: one odd anchor must not cost the whole export (ADR-0009 keeps
      // the format open, so an unknown shape is expected rather than exceptional).
      log.debug("Unreadable annotation anchor; sorting it last", e);
      return UNPLACED;
    }
  }

  /** 1-based page for display, or {@code null} when the annotation is not placed on one. */
  public Integer pageNumber() {
    return placed ? surfaceIndex + 1 : null;
  }
}
