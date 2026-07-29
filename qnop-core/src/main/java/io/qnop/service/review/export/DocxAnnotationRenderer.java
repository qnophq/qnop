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

import io.qnop.service.review.AnnotationService.AnnotationView;
import io.qnop.service.review.AnnotationService.CommentView;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders the export as a Word report (issue #635).
 *
 * <p>Deliberately not a table dumped into a {@code .docx}, or the format would buy nothing over the
 * Excel file. This is a document meant to be <em>read</em> — and, more often than a spreadsheet,
 * edited before it is circulated: a title block naming the review, then one section per annotation
 * in the same reading order, with the facts as a subline and the text as prose.
 *
 * <p>Two deviations from the spreadsheet, both because a page is not a cell. The opening comment is
 * carried in full rather than truncated to a summary, and the comment thread becomes indented
 * paragraphs under its annotation instead of a second sheet. The <em>content</em> is identical —
 * same annotations, same order, same task keys, same resolved author names.
 *
 * <p>Headings use direct character formatting plus an outline level rather than Word's named {@code
 * Heading 1} styles. A blank {@code XWPFDocument} carries no styles part, and building one
 * programmatically depends on schema types that {@code poi-ooxml-lite} does not guarantee. The
 * outline level is what Word's navigation pane actually reads, so the document stays navigable
 * without the brittleness.
 */
@Component
public class DocxAnnotationRenderer implements AnnotationExportRenderer {

  private static final Logger log = LoggerFactory.getLogger(DocxAnnotationRenderer.class);

  /** Half-points, which is how OOXML sizes runs. */
  private static final int TITLE_SIZE = 32;

  private static final int HEADING_SIZE = 26;
  private static final int BODY_SIZE = 22;
  private static final int META_SIZE = 18;

  private static final String MUTED = "6B7280";
  private static final String ACCENT = "1F3A8A";

  /** Twips; one indent step for the thread block. */
  private static final int THREAD_INDENT = 480;

  /**
   * The logo's printed width in points — small enough to sit above the text, not compete with it.
   */
  private static final int LOGO_WIDTH_PT = 90;

  @Override
  public AnnotationExportFormat format() {
    return AnnotationExportFormat.DOCX;
  }

  @Override
  public byte[] render(AnnotationExportModel model) throws IOException {
    try (XWPFDocument document = new XWPFDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writeHeader(document, model);
      writeTitleBlock(document, model);

      if (model.rows().isEmpty()) {
        // An empty review still yields a well-formed document, and says so rather
        // than ending after the title as if the file were truncated.
        XWPFParagraph empty = document.createParagraph();
        empty.setSpacingBefore(240);
        run(empty, "This review has no annotations.", BODY_SIZE, false, MUTED).setItalic(true);
      }

      for (AnnotationExportModel.Row row : model.rows()) {
        writeAnnotation(document, model, row);
      }

      document.write(out);
      return out.toByteArray();
    }
  }

  /**
   * The operator's logo in the page header, right-aligned, on every page.
   *
   * <p>A header rather than a one-off image on page one: a report is printed, split, and pinned to
   * a wall a page at a time, and a page that has left the document should still say who it came
   * from.
   *
   * <p>The logo arrives as PNG in the model — Word cannot embed SVG, and converting is the branding
   * service's job, not a renderer's. A failure here costs the header, never the export: a document
   * without a logo is a document; a download that 500s is not.
   */
  private void writeHeader(XWPFDocument document, AnnotationExportModel model) {
    if (!model.hasLogo()) {
      return;
    }
    try {
      byte[] png = model.logoPng();
      Dimension size = scaled(png);
      XWPFHeader header =
          new XWPFHeaderFooterPolicy(document).createHeader(XWPFHeaderFooterPolicy.DEFAULT);
      XWPFParagraph paragraph = header.getParagraphArray(0);
      if (paragraph == null) {
        paragraph = header.createParagraph();
      }
      paragraph.setAlignment(ParagraphAlignment.RIGHT);
      paragraph
          .createRun()
          .addPicture(
              new ByteArrayInputStream(png),
              Document.PICTURE_TYPE_PNG,
              "logo.png",
              Units.toEMU(size.width()),
              Units.toEMU(size.height()));
    } catch (Exception e) {
      log.warn("Could not place the branding logo in the Word report", e);
    }
  }

  /** The logo at {@link #LOGO_WIDTH_PT}, keeping its aspect ratio. */
  private static Dimension scaled(byte[] png) throws IOException {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
    if (image == null || image.getWidth() <= 0) {
      // A square is a poor guess, but a logo that will not decode has already
      // failed; this only decides how the failure looks if Word accepts it anyway.
      return new Dimension(LOGO_WIDTH_PT, LOGO_WIDTH_PT);
    }
    double ratio = (double) image.getHeight() / image.getWidth();
    return new Dimension(LOGO_WIDTH_PT, Math.max(1, (int) Math.round(LOGO_WIDTH_PT * ratio)));
  }

  /** Printed size in points. */
  private record Dimension(int width, int height) {}

  private void writeTitleBlock(XWPFDocument document, AnnotationExportModel model) {
    XWPFParagraph title = document.createParagraph();
    title.setSpacingAfter(0);
    run(title, model.documentTitle(), TITLE_SIZE, true, null);

    XWPFParagraph subtitle = document.createParagraph();
    subtitle.setSpacingAfter(360);
    List<String> parts = new ArrayList<>();
    parts.add("Annotation report");
    if (model.versionNumber() != null) {
      parts.add("version " + model.versionNumber());
    }
    parts.add(model.rows().size() + (model.rows().size() == 1 ? " annotation" : " annotations"));
    run(subtitle, String.join(" · ", parts), META_SIZE, false, MUTED);

    XWPFParagraph rule = document.createParagraph();
    rule.setBorderBottom(org.apache.poi.xwpf.usermodel.Borders.SINGLE);
    rule.setSpacingAfter(240);
  }

  private void writeAnnotation(
      XWPFDocument document, AnnotationExportModel model, AnnotationExportModel.Row row) {
    AnnotationView view = row.view();

    XWPFParagraph heading = document.createParagraph();
    heading.setSpacingBefore(320);
    heading.setSpacingAfter(0);
    // What Word's navigation pane reads; a named style would need a styles part.
    heading.getCTP().addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.ONE);
    run(heading, headingText(model, row), HEADING_SIZE, true, ACCENT);

    String meta = metaLine(model, row);
    if (!meta.isEmpty()) {
      XWPFParagraph subline = document.createParagraph();
      subline.setSpacingAfter(120);
      run(subline, meta, META_SIZE, false, MUTED);
    }

    if (model.has(AnnotationExportColumn.SUMMARY)) {
      // The full opening comment, not the spreadsheet's 500-character excerpt: a
      // paragraph has room where a cell does not, and a report that clips the
      // finding it reports is worth less than no report.
      writeProse(document, ExportText.plainText(view.firstComment()), 0);
    }

    writeThread(document, model, row);
  }

  /** {@code T-3 · Page 2} — the key first, because that is how people refer to a finding. */
  private String headingText(AnnotationExportModel model, AnnotationExportModel.Row row) {
    StringBuilder text = new StringBuilder();
    if (model.has(AnnotationExportColumn.TASK_KEY)) {
      text.append(row.taskKey());
    }
    if (model.has(AnnotationExportColumn.PAGE) && row.page() != null) {
      if (!text.isEmpty()) {
        text.append(" · ");
      }
      text.append("Page ").append(row.page());
    }
    return text.isEmpty() ? "Annotation" : text.toString();
  }

  /**
   * The facts the user selected, as one muted line under the heading.
   *
   * <p>The wizard's field selection means the same thing here as in the spreadsheet: a deselected
   * field is absent. One mental model, two presentations.
   */
  private String metaLine(AnnotationExportModel model, AnnotationExportModel.Row row) {
    AnnotationView view = row.view();
    List<String> parts = new ArrayList<>();
    if (model.has(AnnotationExportColumn.STATUS)) {
      add(parts, "Status", ExportText.humanize(view.status()));
    }
    if (model.has(AnnotationExportColumn.TYPE)) {
      add(parts, "Type", ExportText.humanize(view.type()));
    }
    if (model.has(AnnotationExportColumn.PRIORITY)) {
      add(parts, "Priority", ExportText.humanize(view.priority()));
    }
    if (model.has(AnnotationExportColumn.AUTHOR)) {
      add(parts, "Author", view.authorDisplayName());
    }
    if (model.has(AnnotationExportColumn.PLACEMENT)) {
      add(parts, "Placement", ExportText.humanize(view.placementStatus()));
    }
    if (model.has(AnnotationExportColumn.REPLIES)) {
      add(parts, "Replies", String.valueOf(row.replies()));
    }
    if (model.has(AnnotationExportColumn.CREATED)) {
      add(parts, "Created", model.formatTimestamp(view.createdAt()));
    }
    if (model.has(AnnotationExportColumn.UPDATED)) {
      add(parts, "Updated", model.formatTimestamp(view.updatedAt()));
    }
    return String.join("   ·   ", parts);
  }

  /**
   * The thread as indented paragraphs, each headed by its author and time.
   *
   * <p>The opening comment is skipped here: it is the annotation's own text and has already been
   * rendered as the section's prose. Repeating it would read as if someone had answered themselves.
   */
  private void writeThread(
      XWPFDocument document, AnnotationExportModel model, AnnotationExportModel.Row row) {
    List<CommentView> replies = row.replyComments();
    if (replies.isEmpty()) {
      return;
    }
    for (CommentView comment : replies) {
      XWPFParagraph attribution = document.createParagraph();
      attribution.setIndentationLeft(THREAD_INDENT);
      attribution.setSpacingBefore(160);
      attribution.setSpacingAfter(0);
      String who =
          comment.authorDisplayName() == null || comment.authorDisplayName().isBlank()
              ? "Unknown"
              : comment.authorDisplayName();
      run(
          attribution,
          who + " · " + model.formatTimestamp(comment.createdAt()),
          META_SIZE,
          true,
          MUTED);
      writeProse(document, ExportText.plainText(comment.body()), THREAD_INDENT);
    }
  }

  /** Writes plain text, keeping its paragraph breaks as real paragraphs. */
  private void writeProse(XWPFDocument document, String text, int indent) {
    if (text == null || text.isBlank()) {
      return;
    }
    for (String block : text.split("\n{2,}")) {
      if (block.isBlank()) {
        continue;
      }
      XWPFParagraph paragraph = document.createParagraph();
      paragraph.setAlignment(ParagraphAlignment.LEFT);
      paragraph.setIndentationLeft(indent);
      paragraph.setSpacingAfter(80);
      // A single newline inside a block is a line break, not a new paragraph.
      String[] lines = block.split("\n");
      for (int index = 0; index < lines.length; index++) {
        XWPFRun run = run(paragraph, lines[index].strip(), BODY_SIZE, false, null);
        if (index < lines.length - 1) {
          run.addBreak();
        }
      }
    }
  }

  private static void add(List<String> parts, String label, String value) {
    if (value != null && !value.isBlank()) {
      parts.add(label + ": " + value);
    }
  }

  private static XWPFRun run(
      XWPFParagraph paragraph, String text, int halfPoints, boolean bold, String colour) {
    XWPFRun run = paragraph.createRun();
    run.setText(text == null ? "" : text);
    run.setFontSize(halfPoints / 2.0);
    run.setBold(bold);
    if (colour != null) {
      run.setColor(colour);
    }
    return run;
  }
}
