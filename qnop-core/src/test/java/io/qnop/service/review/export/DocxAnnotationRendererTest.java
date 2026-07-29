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

import io.qnop.service.review.AnnotationPosition;
import io.qnop.service.review.AnnotationService.AnnotationView;
import io.qnop.service.review.AnnotationService.CommentView;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Word renderer, tested without Spring, a database or Testcontainers (issue #635).
 *
 * <p>This is what the model/renderer split bought: the layout rules are now plain functions over
 * data, so they can be checked in milliseconds instead of behind a container boot. The integration
 * tests still cover what only they can — that the data reaching the renderer is the authorized,
 * privacy-correct set.
 */
class DocxAnnotationRendererTest {

  private final DocxAnnotationRenderer renderer = new DocxAnnotationRenderer();

  private static final Instant WHEN = Instant.parse("2026-03-04T10:15:30Z");

  private static AnnotationView view(String firstComment, String author, int commentCount) {
    return new AnnotationView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        author,
        "OPEN",
        "COMMENT",
        "NORMAL",
        "{}",
        "ANCHORED",
        firstComment,
        commentCount,
        null,
        List.of(),
        WHEN,
        WHEN);
  }

  private static CommentView comment(String author, String body) {
    return new CommentView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        author,
        body,
        List.of(),
        WHEN);
  }

  private static AnnotationExportModel model(List<AnnotationExportModel.Row> rows) {
    return model(rows, null);
  }

  private static AnnotationExportModel model(List<AnnotationExportModel.Row> rows, byte[] logo) {
    return model(rows, logo, ExportDateFormat.ISO);
  }

  private static AnnotationExportModel model(
      List<AnnotationExportModel.Row> rows, byte[] logo, ExportDateFormat dates) {
    return new AnnotationExportModel(
        "Vendor agreement", 3, rows, AnnotationExportColumn.all(), true, logo, dates);
  }

  /** A real PNG, so the renderer's own decode-and-scale path is exercised. */
  private static byte[] logoPng(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private static AnnotationExportModel.Row row(
      String key, AnnotationView view, List<CommentView> thread) {
    return new AnnotationExportModel.Row(
        key, view, new AnnotationPosition(true, 1, 0.2, 0.1, 0), thread);
  }

  /** Every paragraph's text, in document order — what a reader would see. */
  private List<String> paragraphs(byte[] docx) throws Exception {
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
      return document.getParagraphs().stream().map(XWPFParagraph::getText).toList();
    }
  }

  @Test
  @DisplayName("writes a document Word can open, with the review named in the title block")
  void writesAReadableDocument() throws Exception {
    AnnotationView view = view("The indemnity clause is too broad", "Mia Member", 1);
    byte[] docx = renderer.render(model(List.of(row("T-1", view, List.of()))));

    List<String> text = paragraphs(docx);

    assertThat(text).first().isEqualTo("Vendor agreement");
    assertThat(text).element(1).asString().contains("Annotation report", "version 3");
    assertThat(text).contains("The indemnity clause is too broad");
  }

  @Test
  @DisplayName("each annotation is a section headed by its task key and page")
  void headsEachSectionWithKeyAndPage() throws Exception {
    byte[] docx =
        renderer.render(
            model(
                List.of(
                    row("T-1", view("First finding", "Mia Member", 1), List.of()),
                    row("T-2", view("Second finding", "Mia Member", 1), List.of()))));

    // Page 2, because the position's surface index is zero-based.
    assertThat(paragraphs(docx)).contains("T-1 · Page 2", "T-2 · Page 2");
  }

  @Test
  @DisplayName("the meta line carries the selected facts and skips the empty ones")
  void metaLineCarriesTheSelectedFacts() throws Exception {
    AnnotationView view = view("A finding", "Mia Member", 3);
    byte[] docx = renderer.render(model(List.of(row("T-1", view, List.of()))));

    String meta =
        paragraphs(docx).stream().filter(line -> line.startsWith("Status:")).findFirst().orElse("");

    assertThat(meta).contains("Status: Open", "Author: Mia Member", "Replies: 2");
  }

  @Test
  @DisplayName("a deselected field disappears from the report, as it would from the sheet")
  void honoursTheFieldSelection() throws Exception {
    AnnotationView view = view("A finding", "Mia Member", 1);
    AnnotationExportModel narrowed =
        new AnnotationExportModel(
            "Vendor agreement",
            3,
            List.of(row("T-1", view, List.of())),
            AnnotationExportColumn.resolve(List.of("taskKey", "status")),
            false,
            null,
            ExportDateFormat.ISO);

    List<String> text = paragraphs(renderer.render(narrowed));

    assertThat(text).contains("T-1");
    // The author was not selected, so the report must not name them anyway.
    assertThat(text).noneSatisfy(line -> assertThat(line).contains("Mia Member"));
    // Nor the summary, which was equally unselected.
    assertThat(text).doesNotContain("A finding");
  }

  @Test
  @DisplayName("replies appear under their annotation, attributed, without repeating the opener")
  void rendersTheThreadUnderItsAnnotation() throws Exception {
    AnnotationView view = view("The opening remark", "Mia Member", 3);
    List<CommentView> thread =
        List.of(
            comment("Mia Member", "The opening remark"),
            comment("Participant 2", "I disagree"),
            comment("Mia Member", "Fair enough"));

    List<String> text = paragraphs(renderer.render(model(List.of(row("T-1", view, thread)))));

    assertThat(text).contains("I disagree", "Fair enough");
    assertThat(text).anySatisfy(line -> assertThat(line).contains("Participant 2"));
    // The opening comment IS the annotation's prose; repeating it under the
    // section would read as if the author had answered themselves.
    assertThat(text.stream().filter("The opening remark"::equals)).hasSize(1);
  }

  @Test
  @DisplayName("an empty review still yields a valid document that says it is empty")
  void emptyReviewIsStillWellFormed() throws Exception {
    byte[] docx = renderer.render(model(List.of()));

    List<String> text = paragraphs(docx);

    assertThat(text).first().isEqualTo("Vendor agreement");
    assertThat(text).contains("This review has no annotations.");
  }

  @Test
  @DisplayName("the branding logo lands in the page header, scaled to its aspect ratio")
  void placesTheLogoInTheHeader() throws Exception {
    byte[] docx =
        renderer.render(
            model(List.of(row("T-1", view("A finding", "Mia", 1), List.of())), logoPng(252, 128)));

    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
      XWPFHeader header = document.getHeaderList().getFirst();
      List<XWPFPictureData> pictures = header.getAllPictures();

      assertThat(pictures).hasSize(1);
      // 90pt wide, and the height follows the 252:128 source rather than being
      // squashed to a square.
      XWPFRun run = header.getParagraphArray(0).getRuns().getFirst();
      var extent = run.getEmbeddedPictures().getFirst().getCTPicture().getSpPr().getXfrm().getExt();
      assertThat(extent.getCx()).isEqualTo(Units.toEMU(90));
      assertThat(extent.getCy()).isEqualTo(Units.toEMU(46));
    }
  }

  @Test
  @DisplayName("a review with no branding logo still renders, without a header")
  void toleratesAMissingLogo() throws Exception {
    byte[] docx =
        renderer.render(model(List.of(row("T-1", view("A finding", "Mia", 1), List.of()))));

    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
      // No logo means no header at all, rather than an empty band of whitespace
      // at the top of every page.
      assertThat(document.getHeaderList()).isEmpty();
      assertThat(document.getParagraphs()).isNotEmpty();
    }
  }

  @Test
  @DisplayName("timestamps follow the chosen convention, in the meta line and the thread alike")
  void honoursTheChosenDateFormat() throws Exception {
    AnnotationView view = view("A finding", "Mia", 2);
    List<CommentView> thread =
        List.of(comment("Mia", "A finding"), comment("Participant 2", "A reply"));

    List<String> european =
        paragraphs(
            renderer.render(
                model(List.of(row("T-1", view, thread)), null, ExportDateFormat.EUROPEAN)));

    // Both places, not just the column-driven one: a report that dated its
    // headings one way and its replies another would be its own kind of wrong.
    assertThat(european).anySatisfy(line -> assertThat(line).contains("Created: 04.03.2026 10:15"));
    assertThat(european).anySatisfy(line -> assertThat(line).contains("· 04.03.2026 10:15"));
    assertThat(european).noneSatisfy(line -> assertThat(line).contains("2026-03-04"));
  }

  @Test
  @DisplayName("date-only drops the time everywhere")
  void supportsDateOnly() throws Exception {
    AnnotationView view = view("A finding", "Mia", 1);

    List<String> report =
        paragraphs(
            renderer.render(
                model(List.of(row("T-1", view, List.of())), null, ExportDateFormat.DATE_ONLY)));

    assertThat(report).anySatisfy(line -> assertThat(line).contains("Created: 2026-03-04"));
    assertThat(report).noneSatisfy(line -> assertThat(line).contains("10:15"));
  }

  @Test
  @DisplayName("markdown in a comment arrives as readable prose, not as markup")
  void flattensMarkdown() throws Exception {
    AnnotationView view =
        view("**Bold** claim with a [link](https://example.com)\n\nSecond paragraph", "Mia", 1);

    List<String> text = paragraphs(renderer.render(model(List.of(row("T-1", view, List.of())))));

    assertThat(text).contains("Bold claim with a link", "Second paragraph");
  }
}
