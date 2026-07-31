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
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
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
  private static final int LABEL_SIZE = 16;

  private static final String MUTED = "6B7280";
  private static final String INK = "111827";

  /** The thread's hairline: the accent, drained almost to grey. */
  private static final String RULE = "C7D2E4";

  private static final String LINK = "1D4ED8";
  private static final String ACCENT = "1F3A8A";

  /** Twips; one indent step for the thread block. */
  private static final int THREAD_INDENT = 480;

  /** The usable text column on A4 with default margins, in points. */
  private static final int TEXT_COLUMN_PT = 450;

  /** Twips; one indentation step for a nested list item. */
  private static final int LIST_STEP = 240;

  /** Twips; how far a block quote is set in from its surroundings. */
  private static final int QUOTE_STEP = 300;

  private static final int CODE_SIZE = 19;
  private static final String MONO = "Consolas";

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
      writeBody(document, model, row.openingComment(), 0);
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
   * The thread as a discussion, not as an indented list.
   *
   * <p>Indentation alone said "this belongs to the finding above" and nothing else: who answered
   * whom, how many turns it took, and where the exchange ends were all left to the reader. The
   * report is an editorial document, so the discussion is set like one rather than dressed up as a
   * chat — bubbles print badly and read as a screenshot pasted into a memo.
   *
   * <p>Three devices, each carrying one piece of that meaning:
   *
   * <ul>
   *   <li>a labelled opening that states the exchange has begun and how long it is, so a reader
   *       skimming for findings can skip it deliberately;
   *   <li>a continuous hairline rule down the left of every turn, binding them into one
   *       conversation and marking exactly where it stops;
   *   <li>a wash behind the turns written by whoever raised the finding, because "did the author
   *       come back on this?" is the question a review thread is usually read for.
   * </ul>
   *
   * <p>Two states, never one per participant: a report is printed, often in greyscale, and a fourth
   * tint would be noise rather than information.
   */
  private void writeThread(
      XWPFDocument document, AnnotationExportModel model, AnnotationExportModel.Row row) {
    List<CommentView> replies = row.replyComments();
    if (replies.isEmpty()) {
      return;
    }
    writeThreadLabel(document, replies.size());

    String finder = row.view().authorDisplayName();
    for (CommentView comment : replies) {
      int firstParagraph = document.getParagraphs().size();

      XWPFParagraph attribution = document.createParagraph();
      attribution.setIndentationLeft(THREAD_INDENT);
      attribution.setSpacingBefore(140);
      attribution.setSpacingAfter(0);
      String who =
          comment.authorDisplayName() == null || comment.authorDisplayName().isBlank()
              ? "Unknown"
              : comment.authorDisplayName();
      // The finding's author answering their own thread — the turn a reader looks
      // for first. Compared by display name because an anonymous review has no
      // stable id to compare, but keeps a stable pseudonym (ADR-0038).
      boolean byFinder = finder != null && finder.equals(comment.authorDisplayName());

      run(attribution, who, META_SIZE, true, byFinder ? ACCENT : INK);
      run(attribution, "   " + model.formatTimestamp(comment.createdAt()), META_SIZE, false, MUTED);

      writeBody(document, model, comment.body(), THREAD_INDENT, true);
      bindToThread(document, firstParagraph, byFinder);
    }
  }

  /** {@code Discussion · 3 replies} — where the exchange starts, and how long it runs. */
  private void writeThreadLabel(XWPFDocument document, int replies) {
    XWPFParagraph label = document.createParagraph();
    label.setIndentationLeft(THREAD_INDENT);
    label.setSpacingBefore(240);
    label.setSpacingAfter(100);
    label.setBorderTop(Borders.SINGLE);
    hairline(label.getCTP().getPPr().getPBdr().getTop());

    XWPFRun run =
        run(
            label,
            "Discussion · " + replies + (replies == 1 ? " reply" : " replies"),
            LABEL_SIZE,
            true,
            MUTED);
    // Small caps and a little tracking: the editorial way to mark a label as a
    // label without shouting it in full capitals.
    run.setSmallCaps(true);
    run.setCharacterSpacing(12);
  }

  /**
   * Draws every paragraph of one turn into the thread: the rule down its left, weighted in the
   * accent when the turn is the finding author's.
   *
   * <p>Applied to a range after the fact rather than threaded through every writer — prose, images
   * and attachments all create their own paragraphs, and a style parameter on each of them would be
   * four places to forget it.
   */
  private static void bindToThread(XWPFDocument document, int firstParagraph, boolean byFinder) {
    List<XWPFParagraph> paragraphs = document.getParagraphs();
    for (int index = firstParagraph; index < paragraphs.size(); index++) {
      XWPFParagraph paragraph = paragraphs.get(index);
      paragraph.setBorderLeft(Borders.SINGLE);
      CTBorder left = paragraph.getCTP().getPPr().getPBdr().getLeft();
      left.setColor(byFinder ? ACCENT : RULE);
      // Eighths of a point: a hairline for a reply, twice that for the author's,
      // which is the difference that still reads once the colour is gone.
      left.setSz(java.math.BigInteger.valueOf(byFinder ? 12 : 6));
      left.setSpace(java.math.BigInteger.valueOf(10));
    }
  }

  /** A quarter-point rule in the thread's grey — a line, not a box. */
  private static void hairline(CTBorder border) {
    border.setColor(RULE);
    border.setSz(java.math.BigInteger.valueOf(4));
    border.setSpace(java.math.BigInteger.valueOf(4));
  }

  /**
   * Writes a comment body: its markdown, rendered.
   *
   * <p>Word is the format that can show the most of it — emphasis on a run, headings, bullets,
   * quotes, monospace, links in a sentence — so this is where the shared parse pays off most. What
   * it cannot do it degrades rather than drops: a table becomes its rows, a nested list one more
   * indentation step.
   */
  private void writeBody(
      XWPFDocument document, AnnotationExportModel model, String markdown, int indent) {
    writeBody(document, model, markdown, indent, false);
  }

  /**
   * @param bound whether this content sits inside a thread turn
   *     <p>It changes how inner structure is expressed, and the reason is structural rather than
   *     aesthetic: the thread's rule is a paragraph border, so it sits wherever the paragraph's
   *     left edge sits. A code block or a nested list that indents itself therefore bends the line
   *     that is supposed to bind the turn together — which is exactly what a reader notices. Inside
   *     a turn the left edge is constant and nesting shows in the runs; outside one, nothing
   *     constrains it and a quote can be set in behind its own rule.
   */
  private void writeBody(
      XWPFDocument document,
      AnnotationExportModel model,
      String markdown,
      int indent,
      boolean bound) {
    List<ExportBlock> blocks = ExportMarkdown.parse(markdown);
    for (int index = 0; index < blocks.size(); index++) {
      ExportBlock block = blocks.get(index);
      // Rows arrive one block at a time; a table is the run of them, so the loop
      // consumes the whole run at once and hands it over as one table.
      if (block instanceof ExportBlock.TableRow) {
        int end = index;
        while (end < blocks.size() && blocks.get(end) instanceof ExportBlock.TableRow) {
          end++;
        }
        writeTable(document, blocks.subList(index, end), indent);
        index = end - 1;
        continue;
      }
      switch (block) {
        case ExportBlock.Image image -> writeInlineImage(document, model, image, indent);
        case ExportBlock.Attachment file -> writeAttachment(document, model, file, indent);
        case ExportBlock.Code code -> writeCode(document, code, indent, bound);
        case ExportBlock.Divider divider -> writeDivider(document, indent);
        case ExportBlock.Heading heading -> {
          XWPFParagraph paragraph = blockParagraph(document, block, indent, bound);
          paragraph.setSpacingBefore(140);
          // Two steps down from the annotation's own heading, so a heading a
          // commenter wrote reads as structure inside their text and never
          // competes with the report's own.
          writeSpans(paragraph, heading.spans(), sizeForHeading(heading.level()), true, INK);
        }
        case ExportBlock.ListItem item -> {
          XWPFParagraph paragraph =
              blockParagraph(
                  document, block, indent + (bound ? 0 : LIST_STEP * item.depth()), bound);
          paragraph.setSpacingAfter(20);
          // Bound to a thread the depth cannot move the edge, so it shows in the
          // marker instead — the line stays straight and the nesting still reads.
          String lead = bound ? "    ".repeat(item.depth()) : "";
          run(paragraph, lead + item.marker() + "  ", BODY_SIZE, false, MUTED);
          writeSpans(paragraph, item.spans(), BODY_SIZE, false, null);
        }
        // Consumed as a run above; unreachable here, but the sealed switch has to
        // say so rather than fall into a default that would hide a new block type.
        case ExportBlock.TableRow ignored -> {}
        case ExportBlock.Paragraph paragraph ->
            writeSpans(
                blockParagraph(document, block, indent, bound),
                paragraph.spans(),
                BODY_SIZE,
                false,
                null);
      }
    }
  }

  /** A paragraph for one block, set in for its quote depth where that is allowed. */
  private XWPFParagraph blockParagraph(
      XWPFDocument document, ExportBlock block, int indent, boolean bound) {
    XWPFParagraph paragraph = document.createParagraph();
    paragraph.setAlignment(ParagraphAlignment.LEFT);
    paragraph.setSpacingAfter(80);
    int quoted = block.quoteDepth();
    if (bound) {
      // The thread's rule is this paragraph's left border; moving the edge bends
      // it. A quote inside a turn is marked in the type instead.
      paragraph.setIndentationLeft(indent);
      return paragraph;
    }
    paragraph.setIndentationLeft(indent + QUOTE_STEP * quoted);
    if (quoted > 0) {
      // A quote reads as a quote by being set in from a rule, the way a printed
      // document does it — not by being prefixed with ">".
      paragraph.setBorderLeft(Borders.SINGLE);
      hairline(paragraph.getCTP().getPPr().getPBdr().getLeft());
    }
    return paragraph;
  }

  /** Inline content, one run per style combination. */
  private void writeSpans(
      XWPFParagraph paragraph, List<ExportSpan> spans, int size, boolean bold, String colour) {
    for (ExportSpan span : spans) {
      switch (span) {
        case ExportSpan.Break ignored -> {
          if (paragraph.getRuns().isEmpty()) {
            run(paragraph, "", size, bold, colour);
          }
          paragraph.getRuns().getLast().addBreak();
        }
        case ExportSpan.Link link -> {
          if (ExportMarkdown.isSafeHref(link.href())) {
            XWPFRun run = paragraph.createHyperlinkRun(link.href());
            run.setText(ExportMarkdown.flatten(link.spans()));
            run.setFontSize(size / 2.0);
            run.setColor(LINK);
            run.setUnderline(UnderlinePatterns.SINGLE);
          } else {
            // A javascript: or data: target is not made clickable anywhere; the
            // words stay, the link does not.
            writeSpans(paragraph, link.spans(), size, bold, colour);
          }
        }
        case ExportSpan.Text text -> {
          if (text.value().isEmpty()) {
            continue;
          }
          XWPFRun run =
              run(paragraph, text.value(), size, bold || text.has(ExportSpan.Style.BOLD), colour);
          run.setItalic(text.has(ExportSpan.Style.ITALIC));
          if (text.has(ExportSpan.Style.STRIKETHROUGH)) {
            run.setStrikeThrough(true);
          }
          if (text.has(ExportSpan.Style.CODE)) {
            run.setFontFamily(MONO);
          }
        }
      }
    }
  }

  /**
   * A code block: monospace, and set in only where that cannot bend the thread's rule.
   *
   * <p>The inset was unconditional and it was the visible defect: a reply containing code stepped
   * outward, taking the vertical line that binds the discussion with it. Monospace already says
   * "code"; the indentation was decoration, and it lost to a structural line.
   */
  private void writeCode(XWPFDocument document, ExportBlock.Code code, int indent, boolean bound) {
    for (String line : code.text().stripTrailing().split("\n", -1)) {
      XWPFParagraph paragraph =
          blockParagraph(document, code, indent + (bound ? 0 : LIST_STEP), bound);
      paragraph.setSpacingAfter(0);
      XWPFRun run = run(paragraph, line, CODE_SIZE, false, INK);
      run.setFontFamily(MONO);
    }
  }

  /** A thematic break, as a rule rather than a row of dashes. */
  private void writeDivider(XWPFDocument document, int indent) {
    XWPFParagraph paragraph = document.createParagraph();
    paragraph.setIndentationLeft(indent);
    paragraph.setSpacingBefore(80);
    paragraph.setSpacingAfter(80);
    paragraph.setBorderBottom(Borders.SINGLE);
    hairline(paragraph.getCTP().getPPr().getPBdr().getBottom());
  }

  /**
   * A markdown table as a Word table.
   *
   * <p>It was a run of middot-separated lines first, on the theory that a table nested inside a
   * comment inside a thread was more structure than a page could carry. Held against real content
   * that reads as a sentence with punctuation in it, not as a table — in a document that is
   * otherwise properly set, the columns have to be columns.
   *
   * <p>The header row is bold rather than shaded: the report carries no fills anywhere, and one
   * introduced here would print as a grey band that the rest of the document never uses.
   */
  private void writeTable(XWPFDocument document, List<ExportBlock> rows, int indent) {
    int columns =
        rows.stream().mapToInt(row -> ((ExportBlock.TableRow) row).cells().size()).max().orElse(0);
    if (columns == 0) {
      return;
    }
    XWPFTable table = document.createTable(rows.size(), columns);
    table.setWidth("100%");
    // Indented to sit inside whatever contains it — a reply's block, a quote.
    int offset = indent + QUOTE_STEP * rows.getFirst().quoteDepth();
    if (offset > 0) {
      table.getCTTbl().getTblPr().addNewTblInd().setW(java.math.BigInteger.valueOf(offset));
    }

    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      ExportBlock.TableRow source = (ExportBlock.TableRow) rows.get(rowIndex);
      XWPFTableRow row = table.getRow(rowIndex);
      for (int cellIndex = 0; cellIndex < columns; cellIndex++) {
        XWPFTableCell cell = row.getCell(cellIndex);
        XWPFParagraph paragraph = cell.getParagraphArray(0);
        paragraph.setSpacingAfter(0);
        if (cellIndex < source.cells().size()) {
          writeSpans(paragraph, source.cells().get(cellIndex), BODY_SIZE, source.header(), null);
        }
      }
    }
  }

  /** A commenter's heading, sized below the report's own so it never competes with it. */
  private static int sizeForHeading(int level) {
    return Math.max(BODY_SIZE, HEADING_SIZE - 2 - Math.min(level, 4) * 2);
  }

  /**
   * One inline image, scaled to the text column.
   *
   * <p>An image that could not be resolved — deleted, too large for the export's budget, a format
   * Word cannot take — degrades to its alt text in brackets. Silence would leave a sentence
   * pointing at nothing, which is the bug this whole path exists to fix.
   */
  private void writeInlineImage(
      XWPFDocument document, AnnotationExportModel model, ExportBlock.Image image, int indent) {
    ExportImage resolved = model.image(image.url());
    if (resolved == null || !resolved.hasContent()) {
      XWPFParagraph fallback = document.createParagraph();
      fallback.setIndentationLeft(indent);
      fallback.setSpacingAfter(80);
      String label = image.alt() == null || image.alt().isBlank() ? "image" : image.alt();
      run(fallback, "[" + label + "]", BODY_SIZE, false, MUTED).setItalic(true);
      return;
    }
    try {
      Dimension size = fitToColumn(resolved.content(), indent);
      XWPFParagraph paragraph = document.createParagraph();
      paragraph.setIndentationLeft(indent);
      paragraph.setSpacingAfter(120);
      paragraph
          .createRun()
          .addPicture(
              new ByteArrayInputStream(resolved.content()),
              pictureType(resolved.contentType()),
              resolved.fileName() == null ? "image" : resolved.fileName(),
              Units.toEMU(size.width()),
              Units.toEMU(size.height()));
    } catch (Exception e) {
      // The file name is the uploader's, and uploaders name files after people
      // (issue #659). The type and size say enough to diagnose an embed failure.
      log.warn(
          "Could not embed a {} attachment of {} bytes in the Word report",
          resolved.contentType(),
          resolved.content().length,
          e);
    }
  }

  /**
   * An attached file as a marked, clickable row.
   *
   * <p>Not embedded: OOXML can carry an OLE object, but POI has no API to write one, and mailing a
   * report with binaries inside it is a different problem from reading a review. The row instead
   * says what the file is and links to it, so a reader with access is one click away and a reader
   * without at least knows it exists — which the bare filename this replaces did not convey.
   */
  private void writeAttachment(
      XWPFDocument document, AnnotationExportModel model, ExportBlock.Attachment file, int indent) {
    ExportAttachment resolved = model.attachment(file.url());
    String label =
        resolved != null && resolved.fileName() != null && !resolved.fileName().isBlank()
            ? resolved.fileName()
            : (file.label() == null || file.label().isBlank() ? "attachment" : file.label());

    XWPFParagraph paragraph = document.createParagraph();
    paragraph.setIndentationLeft(indent);
    paragraph.setSpacingBefore(60);
    paragraph.setSpacingAfter(60);
    // U+1F4CE, the paperclip: the row has to read as an attachment at a glance,
    // not as a stray sentence in the middle of a thread.
    run(paragraph, "\uD83D\uDCCE ", BODY_SIZE, false, MUTED);

    if (resolved != null && resolved.href() != null && !resolved.href().isBlank()) {
      XWPFRun link = paragraph.createHyperlinkRun(resolved.href());
      link.setText(label);
      link.setFontSize(BODY_SIZE / 2.0);
      link.setColor(LINK);
      link.setUnderline(UnderlinePatterns.SINGLE);
      run(paragraph, "  " + resolved.describe(), META_SIZE, false, MUTED);
    } else {
      // Unresolvable — deleted, or belonging to another review. Naming it still
      // beats the silence, but there is nothing to point at.
      run(paragraph, label, BODY_SIZE, false, MUTED);
    }
  }

  /** Shrinks an image to the text column's width; smaller images keep their size. */
  private static Dimension fitToColumn(byte[] bytes, int indent) throws IOException {
    // Twips to points: the indent eats into the column the picture may occupy.
    int available = TEXT_COLUMN_PT - indent / 20;
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
    if (image == null || image.getWidth() <= 0) {
      return new Dimension(available, available * 3 / 4);
    }
    // Screens are ~96dpi and Word measures in 72dpi points, so a screenshot's
    // pixel width would otherwise render a third too large.
    int naturalPt = (int) Math.round(image.getWidth() * 72.0 / 96.0);
    int width = Math.min(available, Math.max(1, naturalPt));
    double ratio = (double) image.getHeight() / image.getWidth();
    return new Dimension(width, Math.max(1, (int) Math.round(width * ratio)));
  }

  /** POI's picture-type constant for a media type; PNG is the safe default. */
  private static int pictureType(String contentType) {
    return switch (contentType == null ? "" : contentType) {
      case "image/jpeg" -> Document.PICTURE_TYPE_JPEG;
      case "image/gif" -> Document.PICTURE_TYPE_GIF;
      default -> Document.PICTURE_TYPE_PNG;
    };
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
