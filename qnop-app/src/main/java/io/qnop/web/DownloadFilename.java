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
package io.qnop.web;

import java.util.Locale;

/**
 * Builds the download filename for a document version, deriving the extension from the stored
 * content type instead of hardcoding {@code .pdf} (issue #328). PDF is the only ingested format
 * today, but DOCX and Markdown are on the roadmap (see {@code CLAUDE.md}); each new format is one
 * added {@code case} here, so the download seam does not need a rewrite. An unknown content type
 * yields <em>no</em> extension rather than a misleading one.
 */
final class DownloadFilename {

  private DownloadFilename() {}

  static String forVersion(String title, int versionNumber, String contentType) {
    return title + "-v" + versionNumber + extensionFor(contentType);
  }

  /** The dotted file extension for a content type, or an empty string when it is not recognized. */
  static String extensionFor(String contentType) {
    if (contentType == null) {
      return "";
    }
    // Drop any parameters (e.g. "text/markdown; charset=utf-8") and normalize.
    String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return switch (type) {
      case "application/pdf" -> ".pdf";
      case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
      case "text/markdown", "text/x-markdown" -> ".md";
      default -> "";
    };
  }
}
