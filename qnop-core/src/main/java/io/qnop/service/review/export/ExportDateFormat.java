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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/**
 * How timestamps are written in an export (issue #635 follow-up).
 *
 * <p>An export leaves qnop and lands with people whose conventions qnop does not know: {@code
 * 03/04/2026} is two different days depending on which side of the Atlantic reads it, and a report
 * that goes into a German sign-off wants dots. One canonical format cannot serve all of them, so it
 * becomes a choice — made once in the wizard, applied by every format.
 *
 * <p>Each entry carries two patterns because the formats write dates in genuinely different ways. A
 * document renders a string. A spreadsheet must not: the cell holds a real date and this is only
 * its <em>display</em> format, which is what keeps Excel's own sorting and date filters working —
 * the reason the XLSX export types its cells at all (ADR-0052).
 *
 * <p>The timezone travels alongside, because a convention without one is only half an answer: a
 * comment written at 23:40 UTC happened on a different day for the person reading the report in
 * Berlin. The wizard preselects the reader's own zone (ADR-0041) and lets them change it.
 */
public enum ExportDateFormat {
  /** {@code 2026-03-04 14:30} — unambiguous everywhere, and it sorts as text. */
  ISO("iso", "yyyy-MM-dd HH:mm", "yyyy-mm-dd hh:mm"),

  /** {@code 2026-03-04 14:30:07} — when the order of events within a minute matters. */
  ISO_SECONDS("iso-seconds", "yyyy-MM-dd HH:mm:ss", "yyyy-mm-dd hh:mm:ss"),

  /** {@code 04.03.2026 14:30} — the continental European convention. */
  EUROPEAN("european", "dd.MM.yyyy HH:mm", "dd.mm.yyyy hh:mm"),

  /** {@code 03/04/2026 02:30 PM} — the US convention. */
  US("us", "MM/dd/yyyy hh:mm a", "mm/dd/yyyy hh:mm AM/PM"),

  /** {@code 2026-03-04} — for a report where the time of day is noise. */
  DATE_ONLY("date-only", "yyyy-MM-dd", "yyyy-mm-dd");

  /** What a request that names no format gets. */
  public static final ExportDateFormat DEFAULT = ISO;

  private final String id;
  private final String pattern;
  private final String excelPattern;
  private final DateTimeFormatter formatter;

  ExportDateFormat(String id, String pattern, String excelPattern) {
    this.id = id;
    this.pattern = pattern;
    this.excelPattern = excelPattern;
    // Locale-pinned: AM/PM must not become "nachm." because the server happens
    // to run in a German locale. The choice of convention is the user's, made
    // by picking the entry; the rendering of it is not.
    this.formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
  }

  /** Stable wire name — what the export request names. */
  public String getId() {
    return id;
  }

  /** The {@code java.time} pattern, for formats that write a string. */
  public String getPattern() {
    return pattern;
  }

  /** The Excel number format, for formats that write a typed date cell. */
  public String getExcelPattern() {
    return excelPattern;
  }

  /** Formats an instant in the given zone, or an empty string when there is nothing to format. */
  public String format(Instant instant, ZoneId zone) {
    return instant == null
        ? ""
        : formatter.format(instant.atZone(zone == null ? ZoneOffset.UTC : zone));
  }

  /**
   * Resolves a requested id, falling back to {@link #DEFAULT}.
   *
   * <p>Unknown ids fall back rather than fail, for the same reason unknown column ids are ignored:
   * a client one release ahead of its server should get a file, not a 400.
   */
  public static ExportDateFormat fromId(String id) {
    if (id == null || id.isBlank()) {
      return DEFAULT;
    }
    String needle = id.trim().toLowerCase(Locale.ROOT);
    return Arrays.stream(values())
        .filter(format -> format.id.equals(needle))
        .findFirst()
        .orElse(DEFAULT);
  }
}
