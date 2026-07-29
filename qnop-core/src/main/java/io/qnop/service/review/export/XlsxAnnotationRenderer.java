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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
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

  /** Excel's own hard ceiling is 32767; a shorter cap keeps the sheet readable. */
  private static final int SUMMARY_MAX = 500;

  /** Excel's own hard per-cell ceiling, minus room for the ellipsis. */
  private static final int BODY_MAX = 32_000;

  private static final List<String> COMMENT_HEADERS = List.of("#", "Author", "Written", "Comment");

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
      CellStyle dateStyle = dateStyle(workbook);

      Row header = sheet.createRow(0);
      for (int index = 0; index < columns.size(); index++) {
        Cell cell = header.createCell(index);
        cell.setCellValue(columns.get(index).getHeader());
        cell.setCellStyle(headerStyle);
      }

      int rowIndex = 1;
      for (AnnotationExportModel.Row row : model.rows()) {
        writeRow(sheet.createRow(rowIndex++), row, columns, dateStyle);
      }

      // Freeze the header and switch on the filter dropdowns, so Excel's own
      // per-column sort works the moment the file opens — the whole reason the
      // cells are typed rather than pre-formatted strings.
      sheet.createFreezePane(0, 1);
      sheet.setAutoFilter(
          new CellRangeAddress(0, Math.max(model.rows().size(), 1), 0, columns.size() - 1));
      for (int index = 0; index < columns.size(); index++) {
        sheet.setColumnWidth(index, columnWidth(columns.get(index)));
      }

      if (model.includeComments()) {
        writeComments(workbook, model, dateStyle, headerStyle);
      }

      workbook.write(out);
      workbook.dispose(); // drops the streaming temp files
      return out.toByteArray();
    }
  }

  private void writeComments(
      Workbook workbook, AnnotationExportModel model, CellStyle dateStyle, CellStyle headerStyle) {
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
        writeDate(row, 2, comment.createdAt(), dateStyle);
        row.createCell(3).setCellValue(body(comment.body()));
      }
    }

    sheet.createFreezePane(0, 1);
    sheet.setAutoFilter(
        new CellRangeAddress(0, Math.max(rowIndex - 1, 1), 0, COMMENT_HEADERS.size() - 1));
    sheet.setColumnWidth(0, 12 * 256);
    sheet.setColumnWidth(1, 22 * 256);
    sheet.setColumnWidth(2, 20 * 256);
    sheet.setColumnWidth(3, 90 * 256);
  }

  private void writeRow(
      Row row,
      AnnotationExportModel.Row source,
      List<AnnotationExportColumn> columns,
      CellStyle dateStyle) {
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
        case SUMMARY -> row.createCell(index).setCellValue(summary(view.firstComment()));
        case AUTHOR ->
            row.createCell(index)
                .setCellValue(view.authorDisplayName() == null ? "" : view.authorDisplayName());
        case REPLIES -> row.createCell(index).setCellValue(source.replies());
        case PLACEMENT -> row.createCell(index).setCellValue(humanize(view.placementStatus()));
        case CREATED -> writeDate(row, index, view.createdAt(), dateStyle);
        case UPDATED -> writeDate(row, index, view.updatedAt(), dateStyle);
      }
    }
  }

  private static void writeDate(Row row, int column, Instant instant, CellStyle style) {
    if (instant == null) {
      return;
    }
    Cell cell = row.createCell(column);
    // A real Excel date, not a formatted string: sorting and date filters depend on it.
    cell.setCellValue(instant.atOffset(ZoneOffset.UTC).toLocalDateTime());
    cell.setCellStyle(style);
  }

  /**
   * A comment's full text for the thread sheet — unlike the annotation row's {@code summary} this
   * is not an excerpt, because the point of the sheet is to carry what was actually said. Only
   * Excel's own per-cell ceiling truncates it.
   */
  static String body(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.length() <= BODY_MAX ? raw : raw.substring(0, BODY_MAX - 1) + "…";
  }

  /** The opening comment as one flat cell — markdown noise stripped, length capped. */
  static String summary(String firstComment) {
    return ExportText.truncate(ExportText.flatten(firstComment), SUMMARY_MAX);
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

  private static CellStyle dateStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    CreationHelper helper = workbook.getCreationHelper();
    style.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
    return style;
  }

  /** Roughly sized columns; the summary gets the room, the rest stay compact. */
  private static int columnWidth(AnnotationExportColumn column) {
    int characters = column == AnnotationExportColumn.SUMMARY ? 70 : 16;
    return characters * 256;
  }
}
