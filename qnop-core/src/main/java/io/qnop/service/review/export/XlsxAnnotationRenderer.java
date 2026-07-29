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
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
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

  /** Rows reserved above the header when the sheet carries a logo. */
  private static final int LOGO_BAND_ROWS = 4;

  /** The thread sheet's comment column: wider than the summary, since it is the whole point. */
  private static final int COMMENT_WIDTH_CHARS = 90;

  /** The logo's width in pixels on the sheet. */
  private static final int LOGO_WIDTH_PX = 160;

  @Override
  public AnnotationExportFormat format() {
    return AnnotationExportFormat.XLSX;
  }

  @Override
  public byte[] render(AnnotationExportModel model) throws IOException {
    List<AnnotationExportColumn> columns = model.columns();
    // Streaming workbook: a review with thousands of annotations must not have to
    // fit in memory as a DOM.
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("Annotations");
      CellStyle headerStyle = headerStyle(workbook);
      CellStyle dateStyle = dateStyle(workbook, model.dateFormat());
      CellStyle textStyle = wrappedTextStyle(workbook);

      // The logo needs somewhere to float that is not on top of the data, so it
      // gets a band of its own above the header. Without a logo the sheet starts
      // at row 0 exactly as before — the grid's shape follows the content, and a
      // spreadsheet nobody asked to brand keeps its headers in row 1.
      int headerRow = model.hasLogo() ? LOGO_BAND_ROWS : 0;
      if (model.hasLogo()) {
        writeLogoBand(workbook, sheet, model);
      }

      Row header = sheet.createRow(headerRow);
      for (int index = 0; index < columns.size(); index++) {
        Cell cell = header.createCell(index);
        cell.setCellValue(columns.get(index).getHeader());
        cell.setCellStyle(headerStyle);
      }

      int rowIndex = headerRow + 1;
      for (AnnotationExportModel.Row row : model.rows()) {
        writeRow(sheet.createRow(rowIndex++), row, columns, dateStyle, textStyle, model.zone());
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

      if (model.includeComments()) {
        writeComments(workbook, model, dateStyle, headerStyle, textStyle);
      }

      workbook.write(out);
      workbook.dispose(); // drops the streaming temp files
      return out.toByteArray();
    }
  }

  /**
   * The branding logo floating in a band above the header, with the review's name beside it.
   *
   * <p>Anchored rather than placed in a cell: a picture inside the grid would be dragged around by
   * every sort and filter, which is the one thing this sheet exists to support. The band is real
   * rows, so the picture has somewhere to live that the data never reaches.
   */
  private void writeLogoBand(Workbook workbook, Sheet sheet, AnnotationExportModel model) {
    for (int index = 0; index < LOGO_BAND_ROWS; index++) {
      sheet.createRow(index);
    }
    Cell title = sheet.getRow(LOGO_BAND_ROWS - 1).createCell(1);
    title.setCellValue(model.documentTitle());
    title.setCellStyle(headerStyle(workbook));

    try {
      int pictureIndex =
          workbook.addPicture(
              model.logoPng(), org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG);
      Drawing<?> drawing = sheet.createDrawingPatriarch();
      CreationHelper helper = workbook.getCreationHelper();
      ClientAnchor anchor = helper.createClientAnchor();
      anchor.setCol1(0);
      anchor.setRow1(0);
      // A fixed anchor, so resizing a column does not stretch the logo.
      anchor.setAnchorType(ClientAnchor.AnchorType.DONT_MOVE_AND_RESIZE);
      Picture picture = drawing.createPicture(anchor, pictureIndex);
      sizeLogo(picture, model.logoPng());
    } catch (RuntimeException e) {
      // Same contract as everywhere else: a logo is decoration, and a workbook
      // without one beats a download that failed.
      log.warn("Could not place the branding logo in the workbook", e);
    }
  }

  /** Scales the picture to {@link #LOGO_WIDTH_PX} wide, keeping its aspect ratio. */
  private static void sizeLogo(Picture picture, byte[] png) {
    int height = LOGO_WIDTH_PX / 2;
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
      if (image != null && image.getWidth() > 0) {
        height =
            Math.max(1, Math.round(LOGO_WIDTH_PX * (float) image.getHeight() / image.getWidth()));
      }
    } catch (IOException e) {
      log.debug("Could not measure the branding logo; falling back to a default height", e);
    }
    ClientAnchor anchor = picture.getClientAnchor();
    anchor.setDx1(Units.EMU_PER_PIXEL * 4);
    anchor.setDy1(Units.EMU_PER_PIXEL * 4);
    anchor.setCol2(0);
    anchor.setRow2(0);
    anchor.setDx2(Units.EMU_PER_PIXEL * (LOGO_WIDTH_PX + 4));
    anchor.setDy2(Units.EMU_PER_PIXEL * (height + 4));
  }

  private void writeComments(
      Workbook workbook,
      AnnotationExportModel model,
      CellStyle dateStyle,
      CellStyle headerStyle,
      CellStyle textStyle) {
    Sheet sheet = workbook.createSheet("Comments");
    Row header = sheet.createRow(0);
    for (int index = 0; index < COMMENT_HEADERS.size(); index++) {
      Cell cell = header.createCell(index);
      cell.setCellValue(COMMENT_HEADERS.get(index));
      cell.setCellStyle(headerStyle);
    }

    int rowIndex = 1;
    for (AnnotationExportModel.Row annotation : model.rows()) {
      for (CommentView comment : annotation.thread()) {
        Row row = sheet.createRow(rowIndex++);
        row.createCell(0).setCellValue(annotation.taskKey());
        row.createCell(1)
            .setCellValue(comment.authorDisplayName() == null ? "" : comment.authorDisplayName());
        writeDate(row, 2, comment.createdAt(), dateStyle, model.zone());
        Cell text = row.createCell(3);
        text.setCellValue(body(comment.body()));
        text.setCellStyle(textStyle);
      }
    }

    sheet.createFreezePane(0, 1);
    sheet.setAutoFilter(
        new CellRangeAddress(0, Math.max(rowIndex - 1, 1), 0, COMMENT_HEADERS.size() - 1));
    // Mirrors the annotation sheet: the key stays narrow, the author gets a
    // name's worth, the timestamp follows the chosen convention, and the comment
    // takes the rest — it is the column anyone opens this sheet to read.
    sheet.setColumnWidth(0, AnnotationExportColumn.TASK_KEY.getWidthChars() * 256);
    sheet.setColumnWidth(1, AnnotationExportColumn.AUTHOR.getWidthChars() * 256);
    sheet.setColumnWidth(
        2, Math.max(HEADER_ALLOWANCE, model.dateFormat().getPattern().length() + 3) * 256);
    sheet.setColumnWidth(3, COMMENT_WIDTH_CHARS * 256);
  }

  private void writeRow(
      Row row,
      AnnotationExportModel.Row source,
      List<AnnotationExportColumn> columns,
      CellStyle dateStyle,
      CellStyle textStyle,
      ZoneId zone) {
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
          cell.setCellValue(summary(view.firstComment()));
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
   * A comment's full text for the thread sheet — unlike the annotation row's {@code summary} this
   * is not an excerpt, because the point of the sheet is to carry what was actually said. Only
   * Excel's own per-cell ceiling truncates it.
   */
  static String body(String raw) {
    return ExportText.truncate(withImageNames(raw), CELL_MAX);
  }

  /** The opening comment in full — markdown noise stripped, line breaks kept. */
  static String summary(String firstComment) {
    return ExportText.truncate(withImageNames(firstComment), CELL_MAX);
  }

  /**
   * Prose with each image named where it stood.
   *
   * <p>A cell holds text, not pictures — and a floating picture would be worse than none here,
   * since the first sort detaches it from the row it belongs to. So the spreadsheet says {@code
   * [screenshot.png]} where the Word report shows the picture: the reader learns that something
   * visual was said and what it was called, which beats the silence this replaces.
   */
  private static String withImageNames(String markdown) {
    StringBuilder text = new StringBuilder();
    for (ExportSegment segment : ExportSegment.split(markdown)) {
      if (!text.isEmpty()) {
        text.append(' ');
      }
      if (segment instanceof ExportSegment.Text part) {
        // Already plain text with its paragraph breaks intact; flattening again
        // would undo exactly what the wrapped cells are for.
        text.append(part.value());
      } else if (segment instanceof ExportSegment.Image image) {
        String label = image.alt() == null || image.alt().isBlank() ? "image" : image.alt();
        text.append('[').append(label).append(']');
      } else if (segment instanceof ExportSegment.Attachment file) {
        String label = file.label() == null || file.label().isBlank() ? "attachment" : file.label();
        text.append('[').append(label).append(']');
      }
    }
    return text.toString().strip();
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
