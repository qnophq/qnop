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
import java.util.Locale;

/**
 * The file formats an annotation export can produce (issues #547, #635).
 *
 * <p>One place that knows an id, its media type and its extension, so the controller, the filename
 * helper and the renderer lookup cannot drift apart. A further format is an entry here plus a
 * renderer, nothing else.
 */
public enum AnnotationExportFormat {
  XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx", true),
  DOCX(
      "docx",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      ".docx",
      true),
  HTML("html", "text/html;charset=UTF-8", ".html", true),
  PDF("pdf", "application/pdf", ".pdf", true);

  /** What a request that names no format gets — the format that shipped first. */
  public static final AnnotationExportFormat DEFAULT = XLSX;

  private final String id;
  private final String contentType;
  private final String extension;
  private final boolean supportsLogo;

  AnnotationExportFormat(String id, String contentType, String extension, boolean supportsLogo) {
    this.id = id;
    this.contentType = contentType;
    this.extension = extension;
    this.supportsLogo = supportsLogo;
  }

  public String getId() {
    return id;
  }

  public String getContentType() {
    return contentType;
  }

  /** The dotted file extension, including the dot. */
  public String getExtension() {
    return extension;
  }

  /**
   * Whether this format can carry the branding logo at all.
   *
   * <p>A property of the file format, not a preference: Markdown and CSV are text, and there is
   * nowhere in them to put an image. The wizard asks this before offering the choice, so a user is
   * never shown a switch that would silently do nothing.
   */
  public boolean supportsLogo() {
    return supportsLogo;
  }

  /**
   * Resolves a requested format id.
   *
   * <p>An unknown id falls back to {@link #DEFAULT} rather than failing: a client one release ahead
   * of its server should get a file it can open, not a 400. The same reasoning as {@link
   * AnnotationExportColumn#resolve}.
   */
  public static AnnotationExportFormat fromId(String id) {
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
