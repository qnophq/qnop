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
package io.qnop.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.spi.extract.ExtractionException;
import io.qnop.spi.extract.RenderedDocument;
import io.qnop.spi.extract.Surface;
import io.qnop.spi.extract.TextSpan;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PdfBoxDocumentExtractor} (issue #245, ADR-0032). Test PDFs are generated
 * with PDFBox itself, so no binary fixtures live in the repository.
 */
class PdfBoxDocumentExtractorTest {

  private final PdfBoxDocumentExtractor extractor = new PdfBoxDocumentExtractor();

  @Test
  @DisplayName("supports exactly the PDF content type")
  void supportsPdfOnly() {
    assertThat(extractor.supports("application/pdf")).isTrue();
    assertThat(extractor.supports("APPLICATION/PDF")).isTrue();
    assertThat(extractor.supports("image/png")).isFalse();
  }

  @Test
  @DisplayName("extracts one surface per page with crop-box size and ordered, boxed text spans")
  void extractsSurfacesAndSpans() throws Exception {
    byte[] pdf = pdf("Hello qnop reviewers", "Second page text");

    RenderedDocument rendered = extractor.extract(new ByteArrayInputStream(pdf));

    assertThat(rendered.surfaces()).hasSize(2);
    Surface first = rendered.surfaces().get(0);
    assertThat(first.index()).isZero();
    assertThat(first.width()).isEqualTo(PDRectangle.LETTER.getWidth());
    assertThat(first.height()).isEqualTo(PDRectangle.LETTER.getHeight());
    assertThat(joinedText(first)).contains("Hello qnop reviewers");
    assertThat(joinedText(rendered.surfaces().get(1))).contains("Second page text");
  }

  @Test
  @DisplayName("all boxes are normalized to 0..1 and offsets are consistent with the span texts")
  void boxesNormalizedAndOffsetsConsistent() throws Exception {
    byte[] pdf = pdf("A line of text near the top");

    Surface surface = extractor.extract(new ByteArrayInputStream(pdf)).surfaces().get(0);

    assertThat(surface.textSpans()).isNotEmpty();
    int expectedOffset = 0;
    for (TextSpan span : surface.textSpans()) {
      assertThat(span.startOffset()).isEqualTo(expectedOffset);
      assertThat(span.endOffset()).isEqualTo(expectedOffset + span.text().length());
      expectedOffset = span.endOffset() + 1; // the canonical '\n' joiner
      assertThat(span.box().x()).isBetween(0.0, 1.0);
      assertThat(span.box().y()).isBetween(0.0, 1.0);
      assertThat(span.box().width()).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
      assertThat(span.box().height()).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
      assertThat(span.box().x() + span.box().width()).isLessThanOrEqualTo(1.0);
      assertThat(span.box().y() + span.box().height()).isLessThanOrEqualTo(1.0);
    }
  }

  @Test
  @DisplayName("extraction is deterministic — same bytes, same representation")
  void extractionIsDeterministic() throws Exception {
    byte[] pdf = pdf("Determinism matters for job replay");

    RenderedDocument first = extractor.extract(new ByteArrayInputStream(pdf));
    RenderedDocument second = extractor.extract(new ByteArrayInputStream(pdf));

    assertThat(first).isEqualTo(second);
  }

  @Test
  @DisplayName("a page without text yields a surface with an empty text layer")
  void pageWithoutTextHasNoSpans() throws Exception {
    byte[] pdf = pdf(); // one empty page

    RenderedDocument rendered = extractor.extract(new ByteArrayInputStream(pdf));

    assertThat(rendered.surfaces()).hasSize(1);
    assertThat(rendered.surfaces().get(0).textSpans()).isEmpty();
  }

  @Test
  @DisplayName("corrupt content raises the permanent ExtractionException")
  void corruptPdfThrowsExtractionException() {
    byte[] garbage = "%PDF-1.7 this is not really a pdf".getBytes();

    assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(garbage)))
        .isInstanceOf(ExtractionException.class);
  }

  @Test
  @DisplayName("vertically overlapping runs stay character-intact as separate spans (#296)")
  void overlappingRunsAreNotInterleaved() throws Exception {
    // A small kicker line with a large headline right below: the 36 pt glyphs extend upward
    // into the 11 pt line's y-range, which made position sorting merge both lines and
    // interleave their characters by x ("KWOALITäIONrSAmUSSCHeUSpS").
    byte[] pdf;
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
        content.newLineAtOffset(72, 700);
        content.showText("KOALITIONSAUSSCHUSS");
        content.endText();
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 36);
        content.newLineAtOffset(72, 676);
        content.showText("Waermepumpe");
        content.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      pdf = out.toByteArray();
    }

    Surface surface = extractor.extract(new ByteArrayInputStream(pdf)).surfaces().get(0);

    assertThat(surface.textSpans().stream().map(TextSpan::text))
        .containsExactly("KOALITIONSAUSSCHUSS", "Waermepumpe");
  }

  @Test
  @DisplayName("spans are ordered top-to-bottom, then left-to-right, regardless of stream order")
  void spansFollowReadingOrder() throws Exception {
    byte[] pdf;
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        // Drawn bottom-up and right-to-left on purpose: stream order is the reverse of
        // reading order, so the ordering below must come from the geometry.
        for (String[] run :
            new String[][] {
              {"last line", "72", "600"},
              {"right cell", "300", "700"},
              {"left cell", "72", "700"},
            }) {
          content.beginText();
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          content.newLineAtOffset(Float.parseFloat(run[1]), Float.parseFloat(run[2]));
          content.showText(run[0]);
          content.endText();
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      pdf = out.toByteArray();
    }

    Surface surface = extractor.extract(new ByteArrayInputStream(pdf)).surfaces().get(0);

    assertThat(surface.textSpans().stream().map(TextSpan::text))
        .containsExactly("left cell", "right cell", "last line");
    int expectedOffset = 0;
    for (TextSpan span : surface.textSpans()) {
      assertThat(span.startOffset()).isEqualTo(expectedOffset);
      expectedOffset = span.endOffset() + 1;
    }
  }

  private static String joinedText(Surface surface) {
    return String.join("\n", surface.textSpans().stream().map(TextSpan::text).toList());
  }

  /** Builds a PDF with one LETTER page per given line (or one empty page when none). */
  private static byte[] pdf(String... lines) throws IOException {
    try (PDDocument document = new PDDocument()) {
      int pages = Math.max(1, lines.length);
      for (int i = 0; i < pages; i++) {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        if (i < lines.length) {
          try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 700);
            content.showText(lines[i]);
            content.endText();
          }
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  /** Exposes the generator for the app-layer ITs (kept here beside the extractor tests). */
  static InputStream samplePdfStream(String line) throws IOException {
    return new ByteArrayInputStream(pdf(line));
  }
}
