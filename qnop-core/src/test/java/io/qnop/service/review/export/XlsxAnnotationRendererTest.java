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
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The spreadsheet renderer, tested without Spring or a database (issue #635 follow-up). */
class XlsxAnnotationRendererTest {

  private final XlsxAnnotationRenderer renderer = new XlsxAnnotationRenderer();

  private static final Instant WHEN = Instant.parse("2026-03-04T10:15:30Z");

  private static AnnotationView view(String firstComment, int commentCount) {
    return new AnnotationView(
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
        firstComment,
        commentCount,
        null,
        List.of(),
        WHEN,
        WHEN);
  }

  private static AnnotationExportModel model(AnnotationView view, List<CommentView> thread) {
    return new AnnotationExportModel(
        "Vendor agreement",
        3,
        List.of(
            new AnnotationExportModel.Row(
                "T-1", view, new AnnotationPosition(true, 0, 0.1, 0.1, 0), thread)),
        AnnotationExportColumn.all(),
        true,
        null,
        ExportDateFormat.ISO,
        ZoneOffset.UTC,
        Map.of(),
        Map.of());
  }

  private static Sheet sheetOf(byte[] xlsx, int index) throws Exception {
    return new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheetAt(index);
  }

  @Test
  @DisplayName("a long finding is exported whole, not clipped to an excerpt")
  void doesNotTruncateLongText() throws Exception {
    // Comfortably past the 500-character excerpt this replaced.
    String long_ = "Ausführliche Begründung. ".repeat(200);

    byte[] xlsx = renderer.render(model(view(long_, 1), List.of()));

    Cell summary = sheetOf(xlsx, 0).getRow(1).getCell(5);
    assertThat(summary.getStringCellValue()).hasSize(long_.strip().length());
    assertThat(summary.getStringCellValue()).endsWith("Begründung.");
    assertThat(summary.getStringCellValue()).doesNotContain("…");
  }

  @Test
  @DisplayName("paragraph breaks survive, and the cell is set to wrap")
  void keepsParagraphsAndWraps() throws Exception {
    byte[] xlsx = renderer.render(model(view("First point.\n\nSecond point.", 1), List.of()));

    Cell summary = sheetOf(xlsx, 0).getRow(1).getCell(5);

    assertThat(summary.getStringCellValue()).contains("\n");
    // Without wrapping, Excel shows one endless line and hides the rest behind
    // the next column — the reason the cap existed in the first place.
    assertThat(summary.getCellStyle().getWrapText()).isTrue();
    assertThat(summary.getCellStyle().getVerticalAlignment()).isEqualTo(VerticalAlignment.TOP);
  }

  @Test
  @DisplayName("a comment on the thread sheet is unabridged and wraps too")
  void keepsCommentsWhole() throws Exception {
    String body = "Sehr langer Kommentar. ".repeat(150);
    CommentView comment =
        new CommentView(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "Mia",
            body,
            List.of(),
            WHEN);

    byte[] xlsx = renderer.render(model(view("Opening", 2), List.of(comment)));

    Cell cell = sheetOf(xlsx, 1).getRow(1).getCell(3);
    assertThat(cell.getStringCellValue()).hasSize(body.strip().length());
    assertThat(cell.getCellStyle().getWrapText()).isTrue();
  }

  @Test
  @DisplayName("each column is sized for what it holds, not to one default")
  void sizesColumnsIndividually() throws Exception {
    Sheet sheet = sheetOf(renderer.render(model(view("A finding", 1), List.of())), 0);

    // The key needs a fraction of what a name does, and the summary carries
    // wrapped prose — one width for all of them was the previous behaviour.
    int key = sheet.getColumnWidth(0) / 256;
    int author = sheet.getColumnWidth(6) / 256;
    int summary = sheet.getColumnWidth(5) / 256;
    assertThat(key).isLessThan(author);
    assertThat(author).isLessThan(summary);
    // Even the narrowest column keeps room for its header and the filter button.
    for (int index = 0; index < AnnotationExportColumn.all().size(); index++) {
      assertThat(sheet.getColumnWidth(index) / 256)
          .isGreaterThanOrEqualTo(AnnotationExportColumn.all().get(index).getHeader().length() + 2);
    }
  }

  @Test
  @DisplayName("a date-only export stops reserving room for a time nobody asked for")
  void narrowsTheDateColumnsToTheChosenConvention() throws Exception {
    AnnotationExportModel withSeconds = dated(ExportDateFormat.ISO_SECONDS);
    AnnotationExportModel dateOnly = dated(ExportDateFormat.DATE_ONLY);

    int wide = sheetOf(renderer.render(withSeconds), 0).getColumnWidth(9);
    int narrow = sheetOf(renderer.render(dateOnly), 0).getColumnWidth(9);

    assertThat(narrow).isLessThan(wide);
  }

  private static AnnotationExportModel dated(ExportDateFormat dates) {
    return new AnnotationExportModel(
        "Vendor agreement",
        3,
        List.of(
            new AnnotationExportModel.Row(
                "T-1",
                view("A finding", 1),
                new AnnotationPosition(true, 0, 0.1, 0.1, 0),
                List.of())),
        AnnotationExportColumn.all(),
        false,
        null,
        dates,
        ZoneOffset.UTC,
        Map.of(),
        Map.of());
  }

  @Test
  @DisplayName("only Excel's own ceiling truncates, and it says so with an ellipsis")
  void stopsAtExcelsLimit() throws Exception {
    String enormous = "x".repeat(40_000);

    byte[] xlsx = renderer.render(model(view(enormous, 1), List.of()));

    // 32767 is the format's limit, not ours: POI throws past it, so the cut is
    // unavoidable — but it is marked rather than silent.
    String value = sheetOf(xlsx, 0).getRow(1).getCell(5).getStringCellValue();
    assertThat(value).hasSize(32_767);
    assertThat(value).endsWith("…");
  }
}
