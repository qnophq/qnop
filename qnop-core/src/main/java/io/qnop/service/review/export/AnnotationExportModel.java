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
package io.qnop.service.review.export;

import io.qnop.service.review.AnnotationPosition;
import io.qnop.service.review.AnnotationService.AnnotationView;
import io.qnop.service.review.AnnotationService.CommentView;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Everything an export needs, with nothing left to fetch (issue #635).
 *
 * <p>This is the seam between reading and rendering. {@code AnnotationExportService} does the part
 * that must not be duplicated per format — the authorized read through {@code AnnotationService},
 * the reading order, the task keys, the scope filter — and hands the result over as plain data. A
 * renderer receives this and produces bytes; it cannot reach the database, so it cannot
 * accidentally bypass the visibility rules or the identity resolution (ADR-0038) that the model
 * already applied.
 *
 * <p>The consequence worth stating: the comment threads are resolved <em>here</em>, not lazily
 * inside a renderer. That costs memory for the duration of one export and buys renderers that are
 * pure functions over data — unit-testable without Spring, without a database, without
 * Testcontainers.
 *
 * @param documentTitle names the review; the download filename is built from it
 * @param versionNumber the version whose placements decided the positions, null if none exists
 * @param rows the annotations to render, already in reading order
 * @param columns which facts the user asked for, in their fixed order
 * @param includeComments whether {@link Row#thread()} was populated at all
 * @param logoPng the operator's branding logo as PNG, or null when there is none to embed
 * @param dateFormat how every timestamp in this export is written
 * @param zone which timezone those timestamps are expressed in
 */
public record AnnotationExportModel(
    String documentTitle,
    Integer versionNumber,
    List<Row> rows,
    List<AnnotationExportColumn> columns,
    boolean includeComments,
    byte[] logoPng,
    ExportDateFormat dateFormat,
    ZoneId zone) {

  public AnnotationExportModel {
    rows = List.copyOf(rows);
    columns = List.copyOf(columns);
    logoPng = logoPng == null ? null : logoPng.clone();
    dateFormat = dateFormat == null ? ExportDateFormat.DEFAULT : dateFormat;
    zone = zone == null ? ZoneOffset.UTC : zone;
  }

  /** A timestamp in this export's chosen convention and zone. */
  public String formatTimestamp(java.time.Instant instant) {
    return dateFormat.format(instant, zone);
  }

  /**
   * The branding logo, already in a form every format can embed.
   *
   * <p>It arrives through the model like everything else rather than being fetched by the renderer:
   * a renderer that could reach the branding service could reach anything, and the whole point of
   * the split is that it cannot.
   */
  public byte[] logoPng() {
    return logoPng == null ? null : logoPng.clone();
  }

  /** Whether there is a logo to place at all. */
  public boolean hasLogo() {
    return logoPng != null && logoPng.length > 0;
  }

  /** Whether a given fact was selected — the question every renderer asks per row. */
  public boolean has(AnnotationExportColumn column) {
    return columns.contains(column);
  }

  /**
   * One annotation, with the facts a renderer needs and nothing it would have to look up.
   *
   * @param taskKey {@code T-1}, {@code T-2}, … numbering the whole review, not this slice
   * @param position parsed out of the opaque anchor; carries the page number
   * @param thread oldest comment first, empty when threads were not requested
   */
  public record Row(
      String taskKey, AnnotationView view, AnnotationPosition position, List<CommentView> thread) {

    public Row {
      thread = List.copyOf(thread);
    }

    /** The page this annotation sits on, or null when it could not be placed. */
    public Integer page() {
      return position.pageNumber();
    }

    /**
     * Answers, excluding the opening comment.
     *
     * <p>The opening comment <em>is</em> the annotation, so a fresh one would otherwise read as "1
     * comment" when nobody has answered yet.
     */
    public int replies() {
      return Math.max(0, view.commentCount() - 1);
    }

    /** The thread without its opening comment — the replies as text. */
    public List<CommentView> replyComments() {
      return thread.isEmpty() ? List.of() : thread.subList(1, thread.size());
    }
  }
}
