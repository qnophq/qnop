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
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.xml.namespace.QName;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.streaming.SXSSFPicture;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.xmlbeans.XmlCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders the export as an Excel workbook (issue #547, ADR-0052).
 *
 * <p>A grid, because that is what a spreadsheet is for: cells are typed — numbers as numbers,
 * timestamps as real Excel dates — with a frozen header and auto-filter switched on, so Excel's own
 * sorting and filtering work the moment the file opens rather than after a round of cleanup.
 *
 * <p>Comment threads go on a second sheet, one row per comment keyed back by task key. A thread has
 * no fixed length, so it cannot become columns on the annotation row, and folding a whole
 * conversation into one cell would be neither sortable nor readable. A relational second sheet is
 * what a spreadsheet is actually good at.
 */
@Component
public class XlsxAnnotationRenderer implements AnnotationExportRenderer {

  private static final Logger log = LoggerFactory.getLogger(XlsxAnnotationRenderer.class);

  /**
   * The only limit left: Excel's own, which POI enforces by throwing.
   *
   * <p>There used to be a shorter cap on the summary column, on the theory that a spreadsheet wants
   * short cells. It was the wrong call — an export that quietly drops the second half of a finding
   * is worse than a tall row. Cells wrap and grow instead, and nothing is cut until the file format
   * itself refuses.
   */
  private static final int CELL_MAX = SpreadsheetVersion.EXCEL2007.getMaxTextLength();

  private static final List<String> COMMENT_HEADERS = List.of("#", "Author", "Written", "Comment");

  private static final List<String> ATTACHMENT_HEADERS = List.of("#", "File", "Type", "Size");

  /** The thread sheet's comment column: wider than the summary, since it is the whole point. */
  private static final int COMMENT_WIDTH_CHARS = 90;

  /** Breathing room between the logo and the sheet's top-left corner, in pixels. */
  private static final int LOGO_MARGIN_PX = 6;

  /**
   * How much of the branding rendition a sheet shows.
   *
   * <p>Halved here rather than in the rendition itself: one rendition serves both formats, and Word
   * scales it down to about 90pt where the extra pixels are print sharpness. Shrinking the source
   * would cost Word that and gain nothing a scale factor cannot.
   */
  private static final double LOGO_SCALE = 0.5;

  @Override
  public AnnotationExportFormat format() {
    return AnnotationExportFormat.XLSX;
  }

  @Override
  public byte[] render(AnnotationExportModel model) throws IOException {
    List<AnnotationExportColumn> columns = model.columns();
    // Streaming workbook: a review with thousands of annotations must not have to
    // fit in memory as a DOM.
    //
    // The shared-strings table is on, and that is not the default — measured: with
    // it off, a rich string's formatting runs are written away and a cell comes
    // back as plain text, so the emphasis a comment carries would silently vanish.
    // It costs memory proportional to the distinct strings, which this export
    // already spends on the model it holds anyway.
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(null, 200, false, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("Annotations");
      CellStyle headerStyle = headerStyle(workbook);
      CellStyle dateStyle = dateStyle(workbook, model.dateFormat());
      CellStyle textStyle = wrappedTextStyle(workbook);
      FontCache fonts = new FontCache(workbook);

      // A band of its own when there is a logo. Floating it over the grid was the
      // literal reading of "on top" and the wrong one: it covered the cells
      // underneath. The band's height comes from the picture, so the logo is
      // never squeezed to fit a row — the row is sized to fit the logo.
      int headerRow = model.hasLogo() ? reserveLogoRow(sheet, model) : 0;

      Row header = sheet.createRow(headerRow);
      for (int index = 0; index < columns.size(); index++) {
        Cell cell = header.createCell(index);
        cell.setCellValue(columns.get(index).getHeader());
        cell.setCellStyle(headerStyle);
      }
      int rowIndex = headerRow + 1;
      for (AnnotationExportModel.Row row : model.rows()) {
        writeRow(
            sheet.createRow(rowIndex++),
            row,
            columns,
            dateStyle,
            textStyle,
            model.zone(),
            workbook,
            fonts);
      }

      // Freeze the header and switch on the filter dropdowns, so Excel's own
      // per-column sort works the moment the file opens — the whole reason the
      // cells are typed rather than pre-formatted strings.
      sheet.createFreezePane(0, headerRow + 1);
      sheet.setAutoFilter(
          new CellRangeAddress(
              headerRow, Math.max(rowIndex - 1, headerRow + 1), 0, columns.size() - 1));
      for (int index = 0; index < columns.size(); index++) {
        sheet.setColumnWidth(index, width(columns.get(index), model.dateFormat()));
      }
      // After the widths, because where the logo sits depends on them.
      placeLogo(workbook, sheet, model, columns.size());

      if (model.includeComments()) {
        writeComments(workbook, model, dateStyle, headerStyle, textStyle, fonts);
      }
      writeAttachments(workbook, model, headerStyle);

      workbook.write(out);
      workbook.dispose(); // drops the streaming temp files
      return out.toByteArray();
    }
  }

  /**
   * The branding logo, floating over the top-right corner of a sheet.
   *
   * <p>A layer rather than a cell: an image sized to fit a cell is an image nobody chose the size
   * of, and the previous attempt squashed it into one. The picture keeps its own dimensions exactly
   * — the anchor is computed from them, never the other way round — and floats above the grid, so
   * no row is spent on branding and the sheet has the same shape whether or not one is placed.
   *
   * <p>Anchored so it ends at the last column's right edge. That is arithmetic on the column widths
   * this renderer just set, which is also why it runs after them.
   */
  private void placeLogo(
      Workbook workbook, Sheet sheet, AnnotationExportModel model, int columnCount) {
    if (!model.hasLogo()) {
      return;
    }
    try {
      byte[] png = model.logoPng();
      java.awt.Dimension size = displaySize(png);
      long width = Units.pixelToEMU(size.width);
      long height = Units.pixelToEMU(size.height);
      long margin = Units.pixelToEMU(LOGO_MARGIN_PX);
      long left = margin;

      // All four edges, each resolved into the cell that contains it plus the
      // offset inside it. Excel derives the picture's rectangle from these two
      // markers, so getting one wrong does not move the image — it deforms it,
      // which is exactly what a to-marker pinned to the start cell did.
      Marker topLeft =
          new Marker(
              cellAt(
                  left, columnCount, index -> Units.columnWidthToEMU(sheet.getColumnWidth(index))),
              cellAt(margin, Integer.MAX_VALUE, index -> rowHeight(sheet, index)));
      Marker bottomRight =
          new Marker(
              cellAt(
                  left + width,
                  columnCount,
                  index -> Units.columnWidthToEMU(sheet.getColumnWidth(index))),
              cellAt(margin + height, Integer.MAX_VALUE, index -> rowHeight(sheet, index)));

      int pictureIndex = workbook.addPicture(png, Workbook.PICTURE_TYPE_PNG);
      ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
      anchor.setCol1(topLeft.column().index());
      anchor.setDx1((int) topLeft.column().offset());
      anchor.setRow1(topLeft.row().index());
      anchor.setDy1((int) topLeft.row().offset());
      anchor.setCol2(bottomRight.column().index());
      anchor.setDx2((int) bottomRight.column().offset());
      anchor.setRow2(bottomRight.row().index());
      anchor.setDy2((int) bottomRight.row().offset());

      Picture picture = sheet.createDrawingPatriarch().createPicture(anchor, pictureIndex);
      pinAnchor(picture);
    } catch (RuntimeException | IOException e) {
      // Same contract as everywhere else: a logo is decoration, and a workbook
      // without one beats a download that failed.
      log.warn("Could not place the branding logo on sheet {}", sheet.getSheetName(), e);
    }
  }

  /**
   * Creates the row the logo lives in, tall enough to hold it, and returns the row the header goes
   * in.
   *
   * <p>One tall row rather than several default ones: the height follows the picture exactly, so
   * there is neither a gap under the logo nor a logo hanging into the headers.
   */
  private int reserveLogoRow(Sheet sheet, AnnotationExportModel model) {
    try {
      java.awt.Dimension size = displaySize(model.logoPng());
      Row band = sheet.createRow(0);
      // Pixels to points, plus the margin above and below.
      band.setHeightInPoints((float) ((size.height + 2.0 * LOGO_MARGIN_PX) * 72.0 / 96.0));
      return 1;
    } catch (RuntimeException | IOException e) {
      // Undecodable: placeLogo will refuse it too, so leave the grid unchanged.
      log.warn("Could not measure the branding logo; the sheet keeps its plain layout", e);
      return 0;
    }
  }

  /** A picture corner: the cell it falls in, and how far into that cell it sits. */
  private record Marker(Position column, Position row) {}

  private record Position(int index, long offset) {}

  /**
   * Resolves an absolute offset into the cell containing it.
   *
   * <p>Deliberately not {@code Picture.resize()}, which does this arithmetic but discards the
   * caller's {@code dx1} — measured — and so cannot place anything anywhere but a cell boundary.
   */
  private static Position cellAt(
      long target, int limit, java.util.function.IntToLongFunction sizeOf) {
    int index = 0;
    long remaining = target;
    while (index < limit - 1) {
      long size = sizeOf.applyAsLong(index);
      if (size <= 0 || remaining < size) {
        break;
      }
      remaining -= size;
      index++;
    }
    return new Position(index, remaining);
  }

  /** A row's height in EMU; rows that do not exist yet carry the sheet's default. */
  private static long rowHeight(Sheet sheet, int index) {
    Row row = sheet.getRow(index);
    return Units.toEMU(row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints());
  }

  /**
   * Pins the picture so cell changes cannot move or stretch it.
   *
   * <p>{@code ClientAnchor.setAnchorType} is the obvious way to say this and does nothing here: the
   * streaming workbook drops it, whether set before {@code createPicture} or after — measured, not
   * assumed. The attribute it stands for is {@code editAs} on the anchor element, so it is written
   * there directly. That is POI's own object model, one level below the convenience method that
   * fails.
   *
   * <p>The guard matters: if a future POI nests pictures differently, this quietly does nothing
   * rather than corrupting an unrelated element.
   */
  private static void pinAnchor(Picture picture) {
    if (!(picture instanceof SXSSFPicture streaming)) {
      return;
    }
    try (XmlCursor cursor = streaming.getCTPicture().newCursor()) {
      if (cursor.toParent() && "twoCellAnchor".equals(cursor.getName().getLocalPart())) {
        cursor.setAttributeText(new QName("editAs"), "absolute");
      }
    }
  }

  /**
   * The size the logo is drawn at: its own pixels, scaled proportionally.
   *
   * <p>Both dimensions by the same factor, always — a picture whose sides scale differently is the
   * deformation this code has already been wrong about once.
   */
  private static java.awt.Dimension displaySize(byte[] png) throws IOException {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
    if (image == null || image.getWidth() <= 0) {
      throw new IOException("branding logo could not be decoded");
    }
    return new java.awt.Dimension(
        Math.max(1, (int) Math.round(image.getWidth() * LOGO_SCALE)),
        Math.max(1, (int) Math.round(image.getHeight() * LOGO_SCALE)));
  }

  /**
   * Every upload the review references, one row each, with a working link.
   *
   * <p>A sheet rather than columns on the annotation row. Excel applies a hyperlink to a whole cell
   * and never to a word inside one, so each upload needs a cell of its own — and putting those on
   * the row means a review whose busiest annotation carries five files gives every row five
   * columns, most of them empty. A sheet holds any number without deforming the grid, and it sorts
   * and filters like the others.
   *
   * <p>The prose keeps a {@code [name]} marker showing where each was mentioned, and the target is
   * the Word report's: the same absolute URL into the app's download page.
   *
   * <p>Images are listed too. Word embeds them and a spreadsheet cannot, so without this a reader
   * would see {@code [screenshot.png]} and have no way to open it.
   */
  private void writeAttachments(
      Workbook workbook, AnnotationExportModel model, CellStyle headerStyle) {
    List<Reference> references = referencedUploads(model);
    if (references.isEmpty()) {
      return;
    }
    Sheet sheet = workbook.createSheet("Attachments");
    int headerRow = model.hasLogo() ? reserveLogoRow(sheet, model) : 0;
    Row header = sheet.createRow(headerRow);
    for (int index = 0; index < ATTACHMENT_HEADERS.size(); index++) {
      Cell cell = header.createCell(index);
      cell.setCellValue(ATTACHMENT_HEADERS.get(index));
      cell.setCellStyle(headerStyle);
    }

    CellStyle linkStyle = linkStyle(workbook);
    CreationHelper helper = workbook.getCreationHelper();
    int rowIndex = headerRow + 1;
    for (Reference reference : references) {
      ExportAttachment upload = reference.upload();
      Row row = sheet.createRow(rowIndex++);
      row.createCell(0).setCellValue(reference.taskKey());
      Cell name = row.createCell(1);
      name.setCellValue(upload.fileName() == null ? "attachment" : upload.fileName());
      if (upload.href() != null && !upload.href().isBlank()) {
        Hyperlink link = helper.createHyperlink(HyperlinkType.URL);
        link.setAddress(upload.href());
        name.setHyperlink(link);
        name.setCellStyle(linkStyle);
      }
      row.createCell(2).setCellValue(upload.kind());
      row.createCell(3).setCellValue(upload.size());
    }

    sheet.createFreezePane(0, headerRow + 1);
    sheet.setAutoFilter(
        new CellRangeAddress(
            headerRow, Math.max(rowIndex - 1, headerRow + 1), 0, ATTACHMENT_HEADERS.size() - 1));
    sheet.setColumnWidth(0, AnnotationExportColumn.TASK_KEY.getWidthChars() * 256);
    sheet.setColumnWidth(1, 48 * 256);
    sheet.setColumnWidth(2, 12 * 256);
    sheet.setColumnWidth(3, 12 * 256);
    placeLogo(workbook, sheet, model, ATTACHMENT_HEADERS.size());
  }

  /** One upload as it was referenced: which annotation mentioned it, and what it is. */
  private record Reference(String taskKey, ExportAttachment upload) {}

  /**
   * The uploads this export can point at, in reading order.
   *
   * <p>Deduplicated per annotation: the same screenshot quoted twice in one thread is one row, but
   * a file referenced from two annotations is listed under both, because the question a reader asks
   * is "what belongs to this finding".
   */
  private static List<Reference> referencedUploads(AnnotationExportModel model) {
    List<Reference> references = new java.util.ArrayList<>();
    for (AnnotationExportModel.Row row : model.rows()) {
      java.util.Set<String> seen = new java.util.LinkedHashSet<>();
      java.util.List<String> bodies = new java.util.ArrayList<>();
      bodies.add(row.openingComment());
      row.thread().forEach(comment -> bodies.add(comment.body()));
      for (String body : bodies) {
        for (String url : ExportMarkdown.uploadUrls(body == null ? "" : body)) {
          ExportAttachment upload = model.attachment(url);
          if (upload != null && seen.add(url)) {
            references.add(new Reference(row.taskKey(), upload));
          }
        }
      }
    }
    return references;
  }

  /** Blue and underlined — Excel does not style a hyperlink on its own. */
  private static CellStyle linkStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setUnderline(Font.U_SINGLE);
    font.setColor(IndexedColors.BLUE.getIndex());
    style.setFont(font);
    return style;
  }

  private void writeComments(
      Workbook workbook,
      AnnotationExportModel model,
      CellStyle dateStyle,
      CellStyle headerStyle,
      CellStyle textStyle,
      FontCache fonts) {
    Sheet sheet = workbook.createSheet("Comments");
    int headerRow = model.hasLogo() ? reserveLogoRow(sheet, model) : 0;
    Row header = sheet.createRow(headerRow);
    for (int index = 0; index < COMMENT_HEADERS.size(); index++) {
      Cell cell = header.createCell(index);
      cell.setCellValue(COMMENT_HEADERS.get(index));
      cell.setCellStyle(headerStyle);
    }
    int rowIndex = headerRow + 1;
    for (AnnotationExportModel.Row annotation : model.rows()) {
      for (CommentView comment : annotation.thread()) {
        Row row = sheet.createRow(rowIndex++);
        row.createCell(0).setCellValue(annotation.taskKey());
        row.createCell(1)
            .setCellValue(comment.authorDisplayName() == null ? "" : comment.authorDisplayName());
        writeDate(row, 2, comment.createdAt(), dateStyle, model.zone());
        Cell text = row.createCell(3);
        text.setCellValue(richBody(workbook, comment.body(), fonts));
        text.setCellStyle(textStyle);
      }
    }

    sheet.createFreezePane(0, headerRow + 1);
    sheet.setAutoFilter(
        new CellRangeAddress(
            headerRow, Math.max(rowIndex - 1, headerRow + 1), 0, COMMENT_HEADERS.size() - 1));
    // Mirrors the annotation sheet: the key stays narrow, the author gets a
    // name's worth, the timestamp follows the chosen convention, and the comment
    // takes the rest — it is the column anyone opens this sheet to read.
    sheet.setColumnWidth(0, AnnotationExportColumn.TASK_KEY.getWidthChars() * 256);
    sheet.setColumnWidth(1, AnnotationExportColumn.AUTHOR.getWidthChars() * 256);
    sheet.setColumnWidth(
        2, Math.max(HEADER_ALLOWANCE, model.dateFormat().getPattern().length() + 3) * 256);
    sheet.setColumnWidth(3, COMMENT_WIDTH_CHARS * 256);
    // Every sheet, not just the first: a workbook's second tab is as likely to be
    // the one printed or screenshotted as its first.
    placeLogo(workbook, sheet, model, COMMENT_HEADERS.size());
  }

  private void writeRow(
      Row row,
      AnnotationExportModel.Row source,
      List<AnnotationExportColumn> columns,
      CellStyle dateStyle,
      CellStyle textStyle,
      ZoneId zone,
      Workbook workbook,
      FontCache fonts) {
    AnnotationView view = source.view();
    for (int index = 0; index < columns.size(); index++) {
      switch (columns.get(index)) {
        case TASK_KEY -> row.createCell(index).setCellValue(source.taskKey());
        case PAGE -> {
          Integer page = source.page();
          if (page != null) {
            // Numeric, not text: "10" must sort after "9" in Excel.
            row.createCell(index).setCellValue(page);
          }
        }
        case STATUS -> row.createCell(index).setCellValue(humanize(view.status()));
        case TYPE -> row.createCell(index).setCellValue(humanize(view.type()));
        case PRIORITY -> row.createCell(index).setCellValue(humanize(view.priority()));
        case SUMMARY -> {
          Cell cell = row.createCell(index);
          cell.setCellValue(richBody(workbook, source.openingComment(), fonts));
          cell.setCellStyle(textStyle);
        }
        case AUTHOR ->
            row.createCell(index)
                .setCellValue(view.authorDisplayName() == null ? "" : view.authorDisplayName());
        case REPLIES -> row.createCell(index).setCellValue(source.replies());
        case PLACEMENT -> row.createCell(index).setCellValue(humanize(view.placementStatus()));
        case CREATED -> writeDate(row, index, view.createdAt(), dateStyle, zone);
        case UPDATED -> writeDate(row, index, view.updatedAt(), dateStyle, zone);
      }
    }
  }

  private static void writeDate(
      Row row, int column, Instant instant, CellStyle style, ZoneId zone) {
    if (instant == null) {
      return;
    }
    Cell cell = row.createCell(column);
    // A real Excel date, not a formatted string: sorting and date filters depend
    // on it. Excel dates carry no zone, so the conversion happens here and the
    // cell holds local wall-clock time in the zone the user picked.
    cell.setCellValue(instant.atZone(zone).toLocalDateTime());
    cell.setCellStyle(style);
  }

  /**
   * A body as one rich cell value: block structure as lines, emphasis as runs.
   *
   * <p>A spreadsheet cell can carry character formatting — {@code applyFont} over a range — so
   * bold, italic, strikethrough and monospace survive here too. What it cannot carry is a link on
   * part of its text: a hyperlink belongs to the whole cell, so a link keeps its words and loses
   * its target, which the attachments sheet then makes good for uploads.
   */
  private static RichTextString richBody(Workbook workbook, String markdown, FontCache fonts) {
    StringBuilder text = new StringBuilder();
    List<int[]> runs = new ArrayList<>();
    List<Set<ExportSpan.Style>> styles = new ArrayList<>();

    for (ExportBlock block : ExportMarkdown.parse(markdown)) {
      if (!text.isEmpty()) {
        // A block that ended mid-sentence — prose interrupted by an image — leaves
        // its separating space behind, and a line ending in one reads as an
        // accident. Trimming here is safe: nothing has been appended past it yet,
        // so no recorded run can shift.
        trimTrailingSpaces(text);
        text.append('\n');
      }
      text.append("    ".repeat(Math.max(0, block.quoteDepth())));
      switch (block) {
        case ExportBlock.ListItem item -> {
          text.append("  ".repeat(item.depth())).append(item.marker()).append(' ');
          append(text, runs, styles, item.spans(), Set.of());
        }
        case ExportBlock.Heading heading ->
            append(text, runs, styles, heading.spans(), Set.of(ExportSpan.Style.BOLD));
        case ExportBlock.Paragraph paragraph ->
            append(text, runs, styles, paragraph.spans(), Set.of());
        case ExportBlock.TableRow row -> {
          for (int index = 0; index < row.cells().size(); index++) {
            if (index > 0) {
              text.append("  |  ");
            }
            append(
                text,
                runs,
                styles,
                row.cells().get(index),
                row.header() ? Set.of(ExportSpan.Style.BOLD) : Set.of());
          }
        }
        case ExportBlock.Code code -> {
          int from = text.length();
          text.append(code.text().stripTrailing());
          runs.add(new int[] {from, text.length()});
          styles.add(Set.of(ExportSpan.Style.CODE));
        }
        case ExportBlock.Image image ->
            text.append('[').append(name(image.alt(), "image")).append(']');
        case ExportBlock.Attachment file ->
            text.append('[').append(name(file.label(), "attachment")).append(']');
        case ExportBlock.Divider ignored -> text.append("———");
      }
    }

    String value = ExportText.truncate(text.toString().strip(), CELL_MAX);
    RichTextString rich = workbook.getCreationHelper().createRichTextString(value);
    for (int index = 0; index < runs.size(); index++) {
      int[] range = runs.get(index);
      // A run the cell-length cap cut away has nothing left to style.
      if (range[0] >= value.length()) {
        continue;
      }
      Font font = fonts.forStyles(styles.get(index));
      if (font != null) {
        rich.applyFont(range[0], Math.min(range[1], value.length()), font);
      }
    }
    return rich;
  }

  private static void trimTrailingSpaces(StringBuilder text) {
    int end = text.length();
    while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
      end--;
    }
    text.setLength(end);
  }

  /** Appends spans, recording where each styled run begins and ends. */
  private static void append(
      StringBuilder text,
      List<int[]> runs,
      List<Set<ExportSpan.Style>> styles,
      List<ExportSpan> spans,
      Set<ExportSpan.Style> inherited) {
    for (ExportSpan span : spans) {
      switch (span) {
        case ExportSpan.Break ignored -> text.append('\n');
        case ExportSpan.Link link -> append(text, runs, styles, link.spans(), inherited);
        case ExportSpan.Text value -> {
          if (value.value().isEmpty()) {
            continue;
          }
          Set<ExportSpan.Style> combined = new java.util.LinkedHashSet<>(inherited);
          combined.addAll(value.styles());
          int from = text.length();
          text.append(value.value());
          if (!combined.isEmpty()) {
            runs.add(new int[] {from, text.length()});
            styles.add(combined);
          }
        }
      }
    }
  }

  private static String name(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /**
   * Fonts by style combination, created once per workbook.
   *
   * <p>A workbook holds a bounded number of fonts and a run-by-run {@code createFont} would mint
   * one per emphasised word.
   */
  private static final class FontCache {
    private final Workbook workbook;
    private final Map<Set<ExportSpan.Style>, Font> fonts = new java.util.HashMap<>();

    FontCache(Workbook workbook) {
      this.workbook = workbook;
    }

    Font forStyles(Set<ExportSpan.Style> styles) {
      if (styles.isEmpty()) {
        return null;
      }
      return fonts.computeIfAbsent(
          Set.copyOf(styles),
          key -> {
            Font font = workbook.createFont();
            font.setBold(key.contains(ExportSpan.Style.BOLD));
            font.setItalic(key.contains(ExportSpan.Style.ITALIC));
            font.setStrikeout(key.contains(ExportSpan.Style.STRIKETHROUGH));
            if (key.contains(ExportSpan.Style.CODE)) {
              font.setFontName("Consolas");
            }
            return font;
          });
    }
  }

  /** {@code CHANGES_REQUESTED} → {@code Changes requested}; null/blank → empty cell. */
  static String humanize(String raw) {
    return ExportText.humanize(raw);
  }

  private static CellStyle headerStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font bold = workbook.createFont();
    bold.setBold(true);
    style.setFont(bold);
    style.setBorderBottom(BorderStyle.THIN);
    return style;
  }

  /**
   * The chosen display format for date cells.
   *
   * <p>Only the display changes: the cell still holds a real date, so Excel's sorting and date
   * filters keep working whichever convention the user picked.
   */
  /**
   * The style for cells that hold whole comments.
   *
   * <p>Wrapping is what makes an unabridged export usable: without it Excel shows one endless line
   * and hides the rest behind the next column. Top alignment because a tall cell whose text starts
   * at the bottom does not line up with the short cells beside it.
   */
  private static CellStyle wrappedTextStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    style.setWrapText(true);
    style.setVerticalAlignment(VerticalAlignment.TOP);
    return style;
  }

  private static CellStyle dateStyle(Workbook workbook, ExportDateFormat format) {
    CellStyle style = workbook.createCellStyle();
    CreationHelper helper = workbook.getCreationHelper();
    style.setDataFormat(helper.createDataFormat().getFormat(format.getExcelPattern()));
    return style;
  }

  /**
   * A column's width in Excel's units (1/256 of a character).
   *
   * <p>The date columns are the one case the registry cannot decide alone: {@code 2026-03-04} and
   * {@code 03/04/2026 02:30 PM} are the same column with very different needs, so the chosen
   * convention sets the width and a date-only export stops reserving room for a time nobody asked
   * for.
   */
  private static int width(AnnotationExportColumn column, ExportDateFormat dates) {
    int characters =
        switch (column) {
          case CREATED, UPDATED -> Math.max(HEADER_ALLOWANCE, dates.getPattern().length() + 3);
          default -> column.getWidthChars();
        };
    return characters * 256;
  }

  /** Enough for the shortest header plus the auto-filter's dropdown button. */
  private static final int HEADER_ALLOWANCE = 11;
}
