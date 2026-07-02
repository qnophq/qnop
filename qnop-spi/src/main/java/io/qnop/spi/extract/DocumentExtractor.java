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

import java.io.InputStream;

/**
 * The per-format extraction seam (ADR-0032 §4, ADR-0010): turns a raw uploaded binary into the
 * canonical {@link RenderedDocument}. Community ships a PDFBox-based PDF implementation (issue
 * #245); further formats (images, Markdown, DOCX via out-of-process conversion) are added
 * implementations behind this same interface — the viewer, anchoring, diff, and workflow are
 * untouched by a new format.
 *
 * <p>Implementations must be deterministic (same bytes → same representation; re-anchoring and job
 * replay rely on it) and stateless/thread-safe.
 */
public interface DocumentExtractor {

  /** Whether this extractor handles the given (server-sniffed) content type. */
  boolean supports(String contentType);

  /**
   * Extracts the canonical representation from the raw content.
   *
   * @param content the original binary; the caller owns and closes the stream
   * @throws ExtractionException if the content itself is unprocessable (permanent — do not retry)
   */
  RenderedDocument extract(InputStream content) throws ExtractionException;
}
