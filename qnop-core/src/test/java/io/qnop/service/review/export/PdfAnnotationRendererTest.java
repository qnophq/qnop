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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.service.convert.OfficeConversionException;
import io.qnop.service.convert.OfficeConverter;
import io.qnop.service.review.AnnotationPosition;
import io.qnop.service.review.AnnotationService.AnnotationView;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The PDF renderer (issue #639).
 *
 * <p>It converts through a subprocess, which no test may assume exists — so the converter is faked
 * here and what is asserted is the contract around it: that the Word report is what gets converted,
 * and that an absent converter is reported as absence rather than as a failure.
 */
class PdfAnnotationRendererTest {

  private static final Instant WHEN = Instant.parse("2026-03-04T10:15:30Z");

  /** Records what it was handed and returns something recognisable. */
  private static final class FakeConverter implements OfficeConverter {
    private final boolean available;
    byte[] received;
    String receivedExtension;

    FakeConverter(boolean available) {
      this.available = available;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public byte[] toPdf(byte[] source, String sourceExtension) {
      if (!available) {
        throw new OfficeConversionException("no converter");
      }
      received = source;
      receivedExtension = sourceExtension;
      return "%PDF-1.7 converted".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  private static AnnotationExportModel model() {
    AnnotationView view =
        new AnnotationView(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "Mia Member",
            "OPEN",
            "COMMENT",
            "NORMAL",
            "{}",
            "ANCHORED",
            "The indemnity clause is too broad",
            1,
            null,
            List.of(),
            WHEN,
            WHEN);
    return new AnnotationExportModel(
        "Vendor agreement",
        3,
        List.of(
            new AnnotationExportModel.Row(
                "T-1",
                view,
                new AnnotationPosition(true, 0, 0.1, 0.1, 0),
                List.of(),
                view.firstComment())),
        AnnotationExportColumn.all(),
        false,
        null,
        ExportDateFormat.ISO,
        ZoneOffset.UTC,
        Map.of(),
        Map.of());
  }

  @Test
  @DisplayName("converts the Word report rather than drawing a second one")
  void convertsTheWordReport() throws Exception {
    FakeConverter converter = new FakeConverter(true);
    PdfAnnotationRenderer renderer =
        new PdfAnnotationRenderer(new DocxAnnotationRenderer(), converter);

    byte[] pdf = renderer.render(model());

    assertThat(new String(pdf, java.nio.charset.StandardCharsets.UTF_8)).startsWith("%PDF");
    assertThat(converter.receivedExtension).isEqualTo("docx");
    // What went in is the report itself — which is the whole point: one design,
    // one renderer, and a change to the report reaches the PDF for free.
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(converter.received))) {
      assertThat(document.getParagraphs().stream().map(p -> p.getText()).toList())
          .contains("Vendor agreement", "The indemnity clause is too broad");
    }
  }

  @Test
  @DisplayName("reports absence when no converter is installed, instead of failing late")
  void reportsAbsence() {
    PdfAnnotationRenderer renderer =
        new PdfAnnotationRenderer(new DocxAnnotationRenderer(), new FakeConverter(false));

    // A developer machine is the normal case here, and "PDF is not offered" is a
    // very different thing from "your download broke".
    assertThat(renderer.isAvailable()).isFalse();
    assertThatThrownBy(() -> renderer.render(model()))
        .isInstanceOf(OfficeConversionException.class);
  }

  @Test
  @DisplayName("a renderer that only assembles bytes is always available")
  void otherRenderersAreAlwaysAvailable() {
    assertThat(new DocxAnnotationRenderer().isAvailable()).isTrue();
    assertThat(new XlsxAnnotationRenderer().isAvailable()).isTrue();
  }
}
