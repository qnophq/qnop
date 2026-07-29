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
package io.qnop.service.convert;

/**
 * Renders an office document to PDF (issue #639).
 *
 * <p>An interface with one implementation today, because the implementation is a
 * <em>subprocess</em> and everything that depends on it needs a seam it can fake: an office suite
 * is not something a test, a developer machine or a CI runner can be assumed to have.
 *
 * <p>Out-of-process is not an implementation detail either. The conversion tools that do this well
 * are copyleft, and ADR-0007 permits them only as separate processes — never linked into the AGPL
 * core or the commercial add-ons. ADR-0010 already settled the same question for DOCX ingest; this
 * is that decision reused rather than a second one.
 */
public interface OfficeConverter {

  /**
   * Whether a converter is actually installed and answering.
   *
   * <p>Callers are expected to ask before offering a feature that depends on it. A server without
   * the binary is a normal state — every developer machine is one — and it should read as "PDF is
   * not available here", never as an error at download time.
   */
  boolean isAvailable();

  /**
   * Converts a document to PDF.
   *
   * @param source the document's bytes
   * @param sourceExtension the extension the source needs on disk, without a dot ({@code docx})
   * @throws OfficeConversionException if no converter is installed, it fails, or it takes too long
   */
  byte[] toPdf(byte[] source, String sourceExtension);
}
