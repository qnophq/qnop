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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
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
 *
 * <p>Runs are collected in content-stream order — {@code sortByPosition} would merge runs whose
 * y-extents overlap (a large headline beside a small kicker) into one line and interleave their
 * characters by x (issue #296). The finished spans are then ordered geometrically (baseline, then
 * x, then stream order) before offsets are assigned, so the canonical text reads top-to-bottom
 * while every run stays character-intact.
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
    // Spool to a temp file and let PDFBox page from disk (issue #314): loadPDF(byte[]) after
    // readAllBytes held a 50 MB+ PDF fully in the heap; RandomAccessReadBufferedFile reads on
    // demand, bounding memory to PDFBox's own buffers.
    Path spooled = spoolToTempFile(content);
    try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(spooled.toFile()))) {
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
    } finally {
      try {
        Files.deleteIfExists(spooled);
      } catch (IOException e) {
        // Best-effort cleanup; the OS temp dir is swept eventually and a leak is not actionable.
      }
    }
  }

  private static Path spoolToTempFile(InputStream content) {
    Path spooled;
    try {
      spooled = Files.createTempFile("qnop-pdf-", ".pdf");
    } catch (IOException e) {
      // A local-disk failure is an environment problem, not bad content: retryable.
      throw new IllegalStateException("Failed to allocate a temp file for PDF extraction", e);
    }
    try (OutputStream out = Files.newOutputStream(spooled)) {
      content.transferTo(out);
      return spooled;
    } catch (IOException e) {
      try {
        Files.deleteIfExists(spooled);
      } catch (IOException suppressed) {
        e.addSuppressed(suppressed);
      }
      // Failing to read the caller-supplied stream is an I/O problem, not bad content: retryable.
      throw new IllegalStateException("Failed to buffer PDF content", e);
    }
  }

  private Surface extractSurface(PDDocument document, int pageIndex) throws IOException {
    PDRectangle cropBox = document.getPage(pageIndex).getCropBox();
    double width = cropBox.getWidth();
    double height = cropBox.getHeight();

    SpanCollector collector = new SpanCollector(width, height);
    collector.setStartPage(pageIndex + 1); // PDFTextStripper pages are 1-based
    collector.setEndPage(pageIndex + 1);
    // Deliberately NOT setSortByPosition(true): position sorting interleaves the characters of
    // vertically overlapping runs (issue #296). Stream order keeps every run intact; reading
    // order is restored across whole runs in orderedSpans().
    collector.getText(document); // triggers processing; the returned string is not used
    return new Surface(pageIndex, width, height, collector.orderedSpans());
  }

  /**
   * One collected text run before canonical offsets exist: its text, normalized box, optional
   * per-character right edges (issue #290), and the raw geometry (PDF points) plus collection
   * sequence used to establish a deterministic reading order.
   */
  private record RawRun(
      String text,
      NormalizedBox box,
      List<Double> charAdvances,
      double baselineY,
      double minX,
      int seq) {}

  /**
   * Collects one run per stripper-written string with a glyph bounding box normalized to the crop
   * box (values clamped to 0..1 — glyphs may protrude marginally past it). {@link #orderedSpans()}
   * sorts the runs by baseline, then x, then collection order, and only then assigns offsets over
   * the canonical surface text (runs joined by {@code \n}).
   */
  private static final class SpanCollector extends PDFTextStripper {

    private final double pageWidth;
    private final double pageHeight;
    private final List<RawRun> runs = new ArrayList<>();

    private SpanCollector(double pageWidth, double pageHeight) {
      this.pageWidth = pageWidth;
      this.pageHeight = pageHeight;
    }

    List<TextSpan> orderedSpans() {
      List<RawRun> ordered =
          runs.stream()
              .sorted(
                  Comparator.comparingDouble(RawRun::baselineY)
                      .thenComparingDouble(RawRun::minX)
                      .thenComparingInt(RawRun::seq))
              .toList();
      List<TextSpan> spans = new ArrayList<>(ordered.size());
      int offset = 0;
      for (RawRun run : ordered) {
        spans.add(
            new TextSpan(
                run.text(), offset, offset + run.text().length(), run.box(), run.charAdvances()));
        offset += run.text().length() + 1; // +1 for the canonical '\n' joiner between runs
      }
      return spans;
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) {
      if (text.isBlank() || positions.isEmpty()) {
        return;
      }
      // The stripper concatenates same-baseline runs in stream order — a right column drawn
      // before a left one arrives as one string ("right cellleft cell"). Split the positions
      // where the pen jumps backwards in x (or to a different baseline) by more than a glyph
      // height, so each segment is one visually contiguous run. Splitting is only safe when the
      // given text equals the positions' glyphs; otherwise (stripper-inserted separators) keep
      // the original text as one run — never corrupt text.
      StringBuilder glyphs = new StringBuilder();
      for (TextPosition position : positions) {
        glyphs.append(position.getUnicode());
      }
      if (!glyphs.toString().equals(text)) {
        // Stripper-inserted separators have no glyph of their own, so per-character
        // geometry cannot be attributed — consumers fall back to uniform distribution.
        addRun(text, positions, false);
        return;
      }
      List<TextPosition> current = new ArrayList<>();
      TextPosition previous = null;
      for (TextPosition position : positions) {
        if (previous != null && startsNewSegment(previous, position)) {
          addSegment(current);
          current = new ArrayList<>();
        }
        current.add(position);
        previous = position;
      }
      addSegment(current);
    }

    private void addSegment(List<TextPosition> positions) {
      StringBuilder text = new StringBuilder();
      for (TextPosition position : positions) {
        text.append(position.getUnicode());
      }
      addRun(text.toString(), positions, true);
    }

    private static boolean startsNewSegment(TextPosition previous, TextPosition next) {
      double glyphHeight = Math.max(previous.getHeightDir(), next.getHeightDir());
      double previousEndX = previous.getXDirAdj() + previous.getWidthDirAdj();
      boolean jumpsBack = next.getXDirAdj() < previousEndX - glyphHeight;
      boolean changesBaseline =
          Math.abs(next.getYDirAdj() - previous.getYDirAdj()) > glyphHeight / 2.0d;
      return jumpsBack || changesBaseline;
    }

    private void addRun(String text, List<TextPosition> positions, boolean glyphsMatchText) {
      if (text.isBlank()) {
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
      List<Double> advances = glyphsMatchText ? charAdvances(positions) : null;
      runs.add(new RawRun(text, box, advances, maxBottom, minX, runs.size()));
    }

    /**
     * Glyph-true per-character right edges (issue #290), normalized to the crop box. A multi-char
     * position (ligature) distributes its width evenly over its characters. Edges are forced
     * non-decreasing (kerning can nudge a glyph left) so consumers can binary-search them.
     */
    private List<Double> charAdvances(List<TextPosition> positions) {
      List<Double> edges = new ArrayList<>();
      double previousEdge = 0.0d;
      for (TextPosition position : positions) {
        int glyphChars = position.getUnicode().length();
        if (glyphChars == 0) {
          continue;
        }
        double left = position.getXDirAdj();
        double width = position.getWidthDirAdj();
        for (int i = 1; i <= glyphChars; i++) {
          double edge = clamp((left + width * i / glyphChars) / pageWidth);
          previousEdge = Math.max(previousEdge, edge);
          edges.add(previousEdge);
        }
      }
      return edges;
    }

    private static double clamp(double value) {
      return Math.max(0.0d, Math.min(1.0d, value));
    }
  }
}
