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

import io.qnop.spi.extract.DocumentExtractor;
import io.qnop.spi.extract.ExtractionException;
import io.qnop.spi.extract.NormalizedBox;
import io.qnop.spi.extract.RenderedDocument;
import io.qnop.spi.extract.Surface;
import io.qnop.spi.extract.TextSpan;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

/**
 * The Community PDF extractor (issue #245, ADR-0032): PDFBox reads each page and emits one {@link
 * Surface} per page with the crop-box size (PDF points) and the ordered text spans — one span per
 * text run the stripper writes, carrying its glyph bounding box normalized to 0..1 of the crop box.
 *
 * <p>Character offsets are assigned over the surface's canonical text: span texts joined by a
 * single {@code \n}. Extraction is deterministic (same bytes → same representation), which the job
 * replay (ADR-0033) and re-anchoring (ADR-0009) rely on.
 */
@Component
public class PdfBoxDocumentExtractor implements DocumentExtractor {

  static final String PDF_CONTENT_TYPE = "application/pdf";

  @Override
  public boolean supports(String contentType) {
    return PDF_CONTENT_TYPE.equalsIgnoreCase(contentType);
  }

  @Override
  public RenderedDocument extract(InputStream content) throws ExtractionException {
    final byte[] bytes;
    try {
      bytes = content.readAllBytes();
    } catch (IOException e) {
      // Failing to read the caller-supplied stream is an I/O problem, not bad content: retryable.
      throw new IllegalStateException("Failed to read PDF content", e);
    }
    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.isEncrypted()) {
        throw new ExtractionException("Encrypted PDFs are not supported");
      }
      List<Surface> surfaces = new ArrayList<>(document.getNumberOfPages());
      for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
        surfaces.add(extractSurface(document, pageIndex));
      }
      if (surfaces.isEmpty()) {
        throw new ExtractionException("PDF has no pages");
      }
      return new RenderedDocument(surfaces);
    } catch (IOException e) {
      // PDFBox signals unparseable/corrupt input as IOException: the content is bad — permanent.
      throw new ExtractionException("Unreadable PDF: " + e.getMessage(), e);
    }
  }

  private Surface extractSurface(PDDocument document, int pageIndex) throws IOException {
    PDRectangle cropBox = document.getPage(pageIndex).getCropBox();
    double width = cropBox.getWidth();
    double height = cropBox.getHeight();

    SpanCollector collector = new SpanCollector(width, height);
    collector.setStartPage(pageIndex + 1); // PDFTextStripper pages are 1-based
    collector.setEndPage(pageIndex + 1);
    collector.setSortByPosition(true);
    collector.getText(document); // triggers processing; the returned string is not used
    return new Surface(pageIndex, width, height, collector.spans());
  }

  /**
   * Collects one {@link TextSpan} per stripper-written text run, assigning offsets over the
   * canonical surface text (runs joined by {@code \n}) and a glyph bounding box normalized to the
   * crop box. Values are clamped to 0..1 — glyphs may protrude marginally past the crop box.
   */
  private static final class SpanCollector extends PDFTextStripper {

    private final double pageWidth;
    private final double pageHeight;
    private final List<TextSpan> spans = new ArrayList<>();
    private int offset;

    private SpanCollector(double pageWidth, double pageHeight) {
      this.pageWidth = pageWidth;
      this.pageHeight = pageHeight;
    }

    List<TextSpan> spans() {
      return spans;
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) {
      if (text.isBlank() || positions.isEmpty()) {
        return;
      }
      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minTop = Double.MAX_VALUE;
      double maxBottom = -Double.MAX_VALUE;
      for (TextPosition position : positions) {
        minX = Math.min(minX, position.getXDirAdj());
        maxX = Math.max(maxX, position.getXDirAdj() + position.getWidthDirAdj());
        minTop = Math.min(minTop, position.getYDirAdj() - position.getHeightDir());
        maxBottom = Math.max(maxBottom, position.getYDirAdj());
      }
      NormalizedBox box =
          new NormalizedBox(
              clamp(minX / pageWidth),
              clamp(minTop / pageHeight),
              clamp((maxX - minX) / pageWidth),
              clamp((maxBottom - minTop) / pageHeight));
      spans.add(new TextSpan(text, offset, offset + text.length(), box));
      offset += text.length() + 1; // +1 for the canonical '\n' joiner between runs
    }

    private static double clamp(double value) {
      return Math.max(0.0d, Math.min(1.0d, value));
    }
  }
}
