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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The columns an annotation export can carry (issue #547).
 *
 * <p>One registry rather than a list of header strings and a parallel switch of cell writers: the
 * export wizard offers exactly these, the request names them, and every format that follows
 * (#635–#639) renders the same set. A column that exists in one place and not the other is the
 * failure mode this type exists to prevent.
 *
 * <p>Declaration order is the column order. It is deliberately not the caller's to choose — a
 * spreadsheet whose columns arrive in a different order per request is unreadable across two
 * exports of the same review.
 */
public enum AnnotationExportColumn {
  TASK_KEY("taskKey", "#", 9),
  PAGE("page", "Page", 8),
  STATUS("status", "Status", 13),
  TYPE("type", "Type", 15),
  PRIORITY("priority", "Priority", 12),
  SUMMARY("summary", "Summary", 72),
  AUTHOR("author", "Author", 24),
  REPLIES("replies", "Replies", 10),
  PLACEMENT("placement", "Placement", 16),
  CREATED("created", "Created", 19),
  UPDATED("updated", "Updated", 19);

  private final String id;
  private final String header;
  private final int widthChars;

  AnnotationExportColumn(String id, String header, int widthChars) {
    this.id = id;
    this.header = header;
    this.widthChars = widthChars;
  }

  /** Stable wire name — what the export request lists. */
  public String getId() {
    return id;
  }

  /** The human-readable column heading. */
  public String getHeader() {
    return header;
  }

  /**
   * How wide the column should be, in characters.
   *
   * <p>Per column rather than one default with an exception, because the right width is a property
   * of what the column holds: {@code T-12} does not want the room a name does, and a wrapped
   * summary that is too narrow turns every row into a paragraph. Sized so the header — plus the
   * button the auto-filter puts beside it — still reads at the stated width.
   *
   * <p>Fixed rather than auto-sized: POI can only measure columns it has kept in memory, and the
   * export streams rows precisely so a large review never has to fit in memory at once.
   */
  public int getWidthChars() {
    return widthChars;
  }

  public static Optional<AnnotationExportColumn> fromId(String id) {
    if (id == null) {
      return Optional.empty();
    }
    String needle = id.trim().toLowerCase(Locale.ROOT);
    return Arrays.stream(values())
        .filter(column -> column.id.toLowerCase(Locale.ROOT).equals(needle))
        .findFirst();
  }

  /** Everything, in declaration order — the default when a request names no columns. */
  public static List<AnnotationExportColumn> all() {
    return List.of(values());
  }

  /**
   * Resolves a requested selection into columns, in declaration order.
   *
   * <p>Unknown ids are ignored rather than rejected: a client one release ahead of its server would
   * otherwise get an error instead of an export. An empty or entirely unrecognised selection falls
   * back to {@link #all()}, because a sheet with no columns is never what anyone meant.
   */
  public static List<AnnotationExportColumn> resolve(List<String> requestedIds) {
    if (requestedIds == null || requestedIds.isEmpty()) {
      return all();
    }
    Set<AnnotationExportColumn> selected = new LinkedHashSet<>();
    for (String id : requestedIds) {
      fromId(id).ifPresent(selected::add);
    }
    if (selected.isEmpty()) {
      return all();
    }
    return Arrays.stream(values()).filter(selected::contains).toList();
  }
}
