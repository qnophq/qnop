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

import io.qnop.service.convert.OfficeConverter;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Renders the export as PDF by rendering the Word report and converting it (issue #639).
 *
 * <p>The alternative was to draw the report a second time on a PDF canvas. It was rejected on the
 * requirement itself: the PDF is meant to <em>look like</em> the Word report, and a second
 * implementation matches a design only until the next change to either. Everything the report has
 * grown — the running header, the outline levels, the inline images, the attachment links, the
 * discussion's rules — would have to be re-derived by hand, and then kept in step by hand forever.
 *
 * <p>Converting means there is one design and one renderer. A change to the report appears in the
 * PDF without anyone doing anything, because it <em>is</em> the report.
 *
 * <p>The cost is an office suite in the deployment, out-of-process (ADR-0007/0010) — a dependency
 * the DOCX ingest roadmap already commits to, so this reuses an installation rather than asking for
 * one. Where it is absent {@link #isAvailable()} says so and the format is never offered.
 */
@Component
public class PdfAnnotationRenderer implements AnnotationExportRenderer {

  private final DocxAnnotationRenderer word;
  private final OfficeConverter converter;

  public PdfAnnotationRenderer(DocxAnnotationRenderer word, OfficeConverter converter) {
    this.word = word;
    this.converter = converter;
  }

  @Override
  public AnnotationExportFormat format() {
    return AnnotationExportFormat.PDF;
  }

  @Override
  public boolean isAvailable() {
    return converter.isAvailable();
  }

  @Override
  public byte[] render(AnnotationExportModel model) throws IOException {
    return converter.toPdf(word.render(model), "docx");
  }
}
