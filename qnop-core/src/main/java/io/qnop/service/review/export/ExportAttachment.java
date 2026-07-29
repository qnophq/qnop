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

import java.util.Locale;

/**
 * A file attached to an annotation or comment, described well enough to reference (#635 follow-up).
 *
 * <p>Only the metadata, never the bytes. Word has no supported way to embed an arbitrary file —
 * OOXML can carry OLE objects, but POI offers only {@code getAllEmbeddedParts()} for reading — and
 * shipping binaries inside a report that gets mailed onward is its own problem. So the report names
 * the file, says what and how big it is, and links to it.
 *
 * @param fileName the original name, as uploaded
 * @param contentType the stored media type
 * @param sizeBytes the stored size
 * @param href an absolute URL, so the link still works outside the app
 */
public record ExportAttachment(String fileName, String contentType, long sizeBytes, String href) {

  /** {@code XLSX · 84 KB} — the two facts that answer "is this the file I want?". */
  public String describe() {
    return (kind() + " · " + size()).strip();
  }

  /** A short, human name for the type: the extension when there is one, else the subtype. */
  private String kind() {
    int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
    if (dot > 0 && dot < fileName.length() - 1) {
      return fileName.substring(dot + 1).toUpperCase(Locale.ROOT);
    }
    if (contentType == null || !contentType.contains("/")) {
      return "File";
    }
    return contentType.substring(contentType.indexOf('/') + 1).toUpperCase(Locale.ROOT);
  }

  /** Rounded to the unit a reader thinks in; exact byte counts help nobody here. */
  private String size() {
    if (sizeBytes < 1024) {
      return sizeBytes + " B";
    }
    if (sizeBytes < 1024 * 1024) {
      return Math.round(sizeBytes / 1024.0) + " KB";
    }
    return String.format(Locale.ROOT, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
  }
}
