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
import static org.assertj.core.api.Assertions.within;

import io.qnop.service.review.AnnotationPosition;
import io.qnop.service.review.AnnotationService.AnnotationView;
import io.qnop.service.review.AnnotationService.CommentView;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
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

  private static CommentView commentOf(String author, String body) {
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

  private static AnnotationExportModel model(AnnotationView view, List<CommentView> thread) {
    return new AnnotationExportModel(
        "Vendor agreement",
        3,
        List.of(
            new AnnotationExportModel.Row(
                "T-1",
                view,
                new AnnotationPosition(true, 0, 0.1, 0.1, 0),
                thread,
                view.firstComment())),
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
    // A thread is its opening comment plus the replies to it; the sheet lists the
    // replies, because the opening one is the annotation.
    List<CommentView> thread = List.of(commentOf("Mia", "Opening"), commentOf("Mia", body));

    byte[] xlsx = renderer.render(model(view("Opening", 2), thread));

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
                List.of(),
                view("A finding", 1).firstComment())),
        AnnotationExportColumn.all(),
        false,
        null,
        dates,
        ZoneOffset.UTC,
        Map.of(),
        Map.of());
  }

  @Test
  @DisplayName("the logo keeps its proportions, halved, in the top-left corner")
  void placesTheLogoUndistortedAtTopLeft() throws Exception {
    byte[] xlsx = renderer.render(branded(pngOf(240, 120)));

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
      for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
        XSSFSheet sheet = workbook.getSheetAt(index);
        List<XSSFShape> shapes = sheet.getDrawingPatriarch().getShapes();
        assertThat(shapes).as("sheet %s", sheet.getSheetName()).hasSize(1);
        XSSFClientAnchor anchor = (XSSFClientAnchor) shapes.getFirst().getAnchor();

        // The rectangle the markers actually describe. Getting a marker wrong
        // does not move the picture, it deforms it — a to-marker left on the
        // start cell squeezed the logo into one column and one row.
        long left = absoluteX(sheet, anchor.getCol1()) + anchor.getDx1();
        long right = absoluteX(sheet, anchor.getCol2()) + anchor.getDx2();
        long top = absoluteY(sheet, anchor.getRow1()) + anchor.getDy1();
        long bottom = absoluteY(sheet, anchor.getRow2()) + anchor.getDy2();

        // Half of the 240x120 source, both sides by the same factor.
        assertThat((right - left) / (double) Units.EMU_PER_PIXEL)
            .as("width on %s", sheet.getSheetName())
            .isCloseTo(120, within(1.0));
        assertThat((bottom - top) / (double) Units.EMU_PER_PIXEL)
            .as("height on %s", sheet.getSheetName())
            .isCloseTo(60, within(1.0));
        // Which is the same as saying it is not distorted.
        assertThat((right - left) / (double) (bottom - top)).isCloseTo(2.0, within(0.02));

        // Top-left: a hair's margin off both edges of the sheet.
        assertThat(left / (double) Units.EMU_PER_PIXEL).isBetween(0.0, 12.0);
        assertThat(top / (double) Units.EMU_PER_PIXEL).isBetween(0.0, 12.0);
        // And it stays inside its own band rather than reaching into the header.
        assertThat(bottom).isLessThanOrEqualTo(absoluteY(sheet, 1));
      }
    }
  }

  /** The left edge of a column, in EMU from the sheet's origin. */
  private static long absoluteX(Sheet sheet, int column) {
    long emu = 0;
    for (int index = 0; index < column; index++) {
      emu += Units.columnWidthToEMU(sheet.getColumnWidth(index));
    }
    return emu;
  }

  /** The top edge of a row, in EMU from the sheet's origin. */
  private static long absoluteY(Sheet sheet, int row) {
    long emu = 0;
    for (int index = 0; index < row; index++) {
      Row line = sheet.getRow(index);
      emu +=
          Units.toEMU(
              line == null ? sheet.getDefaultRowHeightInPoints() : line.getHeightInPoints());
    }
    return emu;
  }

  @Test
  @DisplayName("the logo is pinned, so a column resize cannot stretch it")
  void pinsTheLogoAgainstCellChanges() throws Exception {
    byte[] xlsx = renderer.render(branded(pngOf(240, 120)));

    // Asserted against the file, not against POI's reader: getAnchorType() does
    // not report editAs back for a streamed workbook, but Excel reads the XML —
    // and the XML is what has to be right.
    List<String> drawings = drawingXml(xlsx);
    assertThat(drawings).hasSize(2);
    assertThat(drawings).allSatisfy(xml -> assertThat(xml).contains("editAs=\"absolute\""));
  }

  /**
   * The drawing parts of a written workbook, as raw XML.
   *
   * <p>Read through OPC rather than {@code ZipInputStream}: POI writes entries with data
   * descriptors, which the streaming zip reader rejects outright.
   */
  private static List<String> drawingXml(byte[] xlsx) throws Exception {
    List<String> parts = new java.util.ArrayList<>();
    try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(xlsx))) {
      for (PackagePart part : pkg.getParts()) {
        if (part.getPartName().getName().startsWith("/xl/drawings/drawing")) {
          parts.add(
              new String(
                  part.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
      }
    }
    return parts;
  }

  @Test
  @DisplayName("the logo gets a row of its own instead of covering the data")
  void logoDoesNotCoverTheGrid() throws Exception {
    Sheet branded = sheetOf(renderer.render(branded(pngOf(240, 120))), 0);
    Sheet plain = sheetOf(renderer.render(model(view("A finding", 1), List.of())), 0);

    // Floating it over the grid was the literal reading of "on top" and hid the
    // cells underneath. The band is tall enough for the picture, so nothing
    // overlaps: header below it, data below that.
    assertThat(branded.getRow(0).getCell(0)).isNull();
    // Tall enough for the halved picture, and no taller than it needs.
    assertThat(branded.getRow(0).getHeightInPoints()).isGreaterThan(60 * 0.75f);
    assertThat(branded.getRow(0).getHeightInPoints()).isLessThan(120 * 0.75f);
    assertThat(branded.getRow(1).getCell(0).getStringCellValue()).isEqualTo("#");
    assertThat(branded.getRow(2).getCell(5).getStringCellValue()).isEqualTo("A finding");
    assertThat(branded.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 2);

    // Unbranded, the grid keeps the shape it had before branding existed.
    assertThat(plain.getRow(0).getCell(0).getStringCellValue()).isEqualTo("#");
  }

  private static byte[] pngOf(int width, int height) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
    return out.toByteArray();
  }

  private static AnnotationExportModel branded(byte[] logo) {
    return new AnnotationExportModel(
        "Vendor agreement",
        3,
        List.of(
            new AnnotationExportModel.Row(
                "T-1",
                view("A finding", 2),
                new AnnotationPosition(true, 0, 0.1, 0.1, 0),
                List.of(
                    new CommentView(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "Mia",
                        "A reply",
                        List.of(),
                        WHEN)),
                "A finding")),
        AnnotationExportColumn.all(),
        true,
        logo,
        ExportDateFormat.ISO,
        ZoneOffset.UTC,
        Map.of(),
        Map.of());
  }

  @Test
  @DisplayName("uploads get a sheet of their own, each with a working link")
  void listsUploadsOnTheirOwnSheet() throws Exception {
    String fileUrl = "/api/v1/documents/d/attachments/a";
    String imageUrl = "/api/v1/documents/d/attachments/b";
    AnnotationView view =
        view("Numbers wrong [v.xlsx](" + fileUrl + ") and ![shot.png](" + imageUrl + ")", 1);
    Map<String, ExportAttachment> uploads =
        Map.of(
            fileUrl,
            new ExportAttachment(
                "v.xlsx", "application/vnd.ms-excel", 86016, "https://q.example/a"),
            imageUrl,
            new ExportAttachment("shot.png", "image/png", 2048, "https://q.example/b"));

    byte[] xlsx = renderer.render(withUploads(view, uploads));
    Sheet sheet = new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheet("Attachments");

    // A sheet, not columns on the row: five files on one annotation would give
    // every row five columns, four of them empty.
    assertThat(sheet).isNotNull();
    assertThat(sheetOf(xlsx, 0).getRow(0).getLastCellNum())
        .isEqualTo((short) AnnotationExportColumn.all().size());

    Cell file = sheet.getRow(1).getCell(1);
    assertThat(file.getStringCellValue()).isEqualTo("v.xlsx");
    // A real hyperlink, not a URL typed into a cell: Excel only makes the former
    // clickable, and a cell is the smallest thing it can attach one to.
    assertThat(file.getHyperlink()).isNotNull();
    assertThat(file.getHyperlink().getAddress()).isEqualTo("https://q.example/a");
    assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("T-1");
    assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("XLSX");
    assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("84 KB");

    // Images too: Word embeds them, a spreadsheet cannot, and "[shot.png]" with
    // no way to open it helps nobody.
    Cell image = sheet.getRow(2).getCell(1);
    assertThat(image.getStringCellValue()).isEqualTo("shot.png");
    assertThat(image.getHyperlink().getAddress()).isEqualTo("https://q.example/b");

    // The prose still marks where each was mentioned.
    assertThat(sheetOf(xlsx, 0).getRow(1).getCell(5).getStringCellValue())
        .contains("[v.xlsx]", "[shot.png]");
  }

  @Test
  @DisplayName("a comment's upload is listed under its annotation's key")
  void listsUploadsFromComments() throws Exception {
    String url = "/api/v1/documents/d/attachments/a";
    CommentView comment =
        new CommentView(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "Mia",
            "Proof: [log.txt](" + url + ")",
            List.of(),
            WHEN);
    AnnotationView opening = view("Opening", 2);
    AnnotationExportModel model =
        new AnnotationExportModel(
            "Vendor agreement",
            3,
            List.of(
                new AnnotationExportModel.Row(
                    "T-1",
                    opening,
                    new AnnotationPosition(true, 0, 0.1, 0.1, 0),
                    List.of(comment),
                    opening.firstComment())),
            AnnotationExportColumn.all(),
            true,
            null,
            ExportDateFormat.ISO,
            ZoneOffset.UTC,
            Map.of(),
            Map.of(url, new ExportAttachment("log.txt", "text/plain", 512, "https://q.example/a")));

    Sheet sheet =
        new XSSFWorkbook(new ByteArrayInputStream(renderer.render(model))).getSheet("Attachments");

    // Filed under the annotation, because "what belongs to this finding" is the
    // question a reader brings to this sheet.
    assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("T-1");
    assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("log.txt");
    assertThat(sheet.getRow(1).getCell(1).getHyperlink().getAddress())
        .isEqualTo("https://q.example/a");
  }

  @Test
  @DisplayName("a review with no uploads gets no attachments sheet")
  void omitsTheSheetWhenThereIsNothingToList() throws Exception {
    byte[] xlsx = renderer.render(model(view("A plain finding", 1), List.of()));

    assertThat(new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheet("Attachments")).isNull();
  }

  private static AnnotationExportModel withUploads(
      AnnotationView view, Map<String, ExportAttachment> uploads) {
    return new AnnotationExportModel(
        "Vendor agreement",
        3,
        List.of(
            new AnnotationExportModel.Row(
                "T-1",
                view,
                new AnnotationPosition(true, 0, 0.1, 0.1, 0),
                List.of(),
                view.firstComment())),
        AnnotationExportColumn.all(),
        false,
        null,
        ExportDateFormat.ISO,
        ZoneOffset.UTC,
        Map.of(),
        uploads);
  }

  @Test
  @DisplayName("emphasis survives into the cell as formatting runs")
  void keepsEmphasisInCells() throws Exception {
    byte[] xlsx = renderer.render(model(view("A **bold** and *italic* claim", 1), List.of()));

    RichTextString rich = sheetOf(xlsx, 0).getRow(1).getCell(5).getRichStringCellValue();

    // Measured, not assumed: with the streaming workbook's shared-strings table
    // off — its default — these runs are written away and the cell comes back as
    // plain text, so the emphasis a comment carries would vanish silently.
    assertThat(rich.getString()).isEqualTo("A bold and italic claim");
    assertThat(rich.numFormattingRuns()).isGreaterThan(1);
  }

  @Test
  @DisplayName("block structure becomes lines in the wrapped cell")
  void keepsBlockStructureInCells() throws Exception {
    byte[] xlsx =
        renderer.render(model(view("## Findings\n\n- first\n- second\n\n> quoted", 1), List.of()));

    String value = sheetOf(xlsx, 0).getRow(1).getCell(5).getStringCellValue();

    // A cell cannot indent or draw a rule, but it has lines — so the shape of the
    // comment survives even where its typography cannot.
    assertThat(value).contains("Findings");
    assertThat(value).contains("• first", "• second");
    assertThat(value).contains("quoted");
    assertThat(value.lines().count()).isGreaterThanOrEqualTo(4);
    // The markup itself never appears.
    assertThat(value).doesNotContain("##", "> quoted", "- first");
  }

  @Test
  @DisplayName("the comments sheet lists replies, never the annotation itself")
  void listsOnlyReplies() throws Exception {
    List<CommentView> thread =
        List.of(
            commentOf("Mia", "The indemnity clause is too broad"),
            commentOf("Participant 2", "Agreed"),
            commentOf("Mia", "Will fix"));

    Sheet sheet =
        sheetOf(renderer.render(model(view("The indemnity clause is too broad", 3), thread)), 1);

    // The opening comment IS the annotation: it is already the Summary column on
    // the first sheet, and a row for it here both duplicates it and contradicts
    // the Replies count beside it, which has always excluded it.
    assertThat(column(sheet, 3)).containsExactly("Agreed", "Will fix");
    assertThat(column(sheet, 3)).doesNotContain("The indemnity clause is too broad");
  }

  /** A column's values below the header, as text. */
  private static List<String> column(Sheet sheet, int index) {
    List<String> values = new java.util.ArrayList<>();
    for (int row = 1; row <= sheet.getLastRowNum(); row++) {
      Row line = sheet.getRow(row);
      Cell cell = line == null ? null : line.getCell(index);
      values.add(cell == null ? "" : cell.getStringCellValue());
    }
    return values;
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
