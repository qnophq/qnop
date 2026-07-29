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
package io.qnop.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The XLSX export end to end (issue #547): the workbook is read back with POI and asserted on, so
 * the test proves an actual spreadsheet rather than "some bytes came out".
 */
class AnnotationExportIT extends SeededIntegrationTest {

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;

  private UUID documentId;

  private void seedDocument(boolean anonymous) {
    Document document = new Document(MEMBER_ID, "Vendor agreement");
    document.setAnonymous(anonymous);
    document.setWorkflowState(WorkflowState.IN_REVIEW);
    documentId = documents.save(document).getId();
    versions.save(
        new DocumentVersion(
            documentId, 1, "sha256/aa/deadbeef", "deadbeef", "application/pdf", 1234L, MEMBER_ID));
    participants.save(ReviewParticipant.forUser(documentId, AUDITOR_ID));
    participants.save(ReviewParticipant.forUser(documentId, MEMBER2_ID));
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  /** Creates an annotation anchored at a given page and position. */
  private void annotate(UUID author, int surface, double x, double y, String comment)
      throws Exception {
    String anchor =
        "{\"region\":{\"surfaceIndex\":"
            + surface
            + ",\"box\":{\"x\":"
            + x
            + ",\"y\":"
            + y
            + ",\"width\":0.3,\"height\":0.05}},\"textQuote\":{\"quote\":\"the clause\"}}";
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), author)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"versionNumber\":1,\"anchor\":"
                        + anchor
                        + ",\"comment\":\""
                        + comment
                        + "\"}"))
        .andExpect(status().isCreated());
  }

  /** Creates an annotation and returns its id, for tests that need to act on it. */
  private String createAnnotationReturningId(
      UUID author, int surface, double x, double y, String comment) throws Exception {
    String anchor =
        "{\"region\":{\"surfaceIndex\":"
            + surface
            + ",\"box\":{\"x\":"
            + x
            + ",\"y\":"
            + y
            + ",\"width\":0.3,\"height\":0.05}},\"textQuote\":{\"quote\":\"the clause\"}}";
    String json =
        mockMvc
            .perform(
                as(post("/api/v1/documents/" + documentId + "/annotations"), author)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"versionNumber\":1,\"anchor\":"
                            + anchor
                            + ",\"comment\":\""
                            + comment
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return com.jayway.jsonpath.JsonPath.read(json, "$.id");
  }

  /**
   * The workbook without branding.
   *
   * <p>Most of these tests are about the grid — which column holds what, which row an annotation
   * lands in — and a logo moves the header down by the height of its band. Asking for the plain
   * sheet keeps each test about one thing; {@link #workbookCarriesTheLogoAboveTheGrid} covers the
   * band itself.
   */
  private Sheet download(UUID actor) throws Exception {
    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("logo", "false"),
                    actor))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        "Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body));
    return workbook.getSheetAt(0);
  }

  /** Downloads with the comment sheet switched on and returns that second sheet. */
  private Sheet downloadComments(UUID actor) throws Exception {
    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("comments", "true")
                        .param("logo", "false"),
                    actor))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body));
    return workbook.getSheet("Comments");
  }

  /** Uploads a real PNG and returns the Markdown URL the composer would write for it. */
  private String uploadImage(UUID actor, String fileName) throws Exception {
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(120, 60, BufferedImage.TYPE_INT_ARGB), "png", png);
    String json =
        mockMvc
            .perform(
                multipart("/api/v1/documents/" + documentId + "/attachments")
                    .file(new MockMultipartFile("file", fileName, "image/png", png.toByteArray()))
                    .header("Authorization", "Bearer " + token(actor)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return com.jayway.jsonpath.JsonPath.read(json, "$.url");
  }

  private void reply(String annotationId, UUID author, String body) throws Exception {
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/comments"), author)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"" + body + "\"}"))
        .andExpect(status().isCreated());
  }

  /** Downloads the Word report and returns its paragraphs, in document order. */
  private List<String> downloadDocx(UUID actor, String... params) throws Exception {
    var request =
        get("/api/v1/documents/" + documentId + "/annotations/export").param("format", "docx");
    for (int index = 0; index + 1 < params.length; index += 2) {
      request = request.param(params[index], params[index + 1]);
    }
    byte[] body =
        mockMvc
            .perform(as(request, actor))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        "Content-Type",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .andExpect(
                header()
                    .string("Content-Disposition", org.hamcrest.Matchers.containsString(".docx")))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(body))) {
      return document.getParagraphs().stream().map(XWPFParagraph::getText).toList();
    }
  }

  private static List<String> column(Sheet sheet, int index) {
    List<String> values = new ArrayList<>();
    for (int row = 1; row <= sheet.getLastRowNum(); row++) {
      Row current = sheet.getRow(row);
      Cell cell = current == null ? null : current.getCell(index);
      values.add(
          cell == null
              ? ""
              : switch (cell.getCellType()) {
                case NUMERIC -> String.valueOf((int) cell.getNumericCellValue());
                default -> cell.getStringCellValue();
              });
    }
    return values;
  }

  @Test
  @DisplayName("the workbook has a header row and one row per annotation, in reading order")
  void exportsInReadingOrder() throws Exception {
    seedDocument(false);
    // Created deliberately out of document order, so the sort has something to do.
    annotate(MEMBER_ID, 1, 0.1, 0.2, "Second page note");
    annotate(AUDITOR_ID, 0, 0.8, 0.6, "Page one, lower right");
    annotate(MEMBER2_ID, 0, 0.1, 0.6, "Page one, lower left");
    annotate(MEMBER_ID, 0, 0.5, 0.1, "Page one, top");

    Sheet sheet = download(MEMBER_ID);

    Row header = sheet.getRow(0);
    assertThat(header.getCell(0).getStringCellValue()).isEqualTo("#");
    assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Page");
    assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Status");
    assertThat(sheet.getLastRowNum()).isEqualTo(4); // 4 annotations + header

    // Page, then top-to-bottom, then left-to-right.
    assertThat(column(sheet, 5))
        .containsExactly(
            "Page one, top", "Page one, lower left", "Page one, lower right", "Second page note");
    assertThat(column(sheet, 1)).containsExactly("1", "1", "1", "2");
    // The status column is populated for every row.
    assertThat(column(sheet, 2))
        .allMatch(value -> value.equals("Open") || value.equals("Resolved"));
  }

  @Test
  @DisplayName("task keys follow creation order, not the export's reading order")
  void taskKeysFollowCreationOrder() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 1, 0.1, 0.1, "Created first, sorts last");
    annotate(MEMBER_ID, 0, 0.1, 0.1, "Created second, sorts first");

    Sheet sheet = download(MEMBER_ID);

    // Reading order puts the page-1 annotation first, but it is still T-2 — the
    // key must agree with the board the user has been looking at.
    assertThat(column(sheet, 5))
        .containsExactly("Created second, sorts first", "Created first, sorts last");
    assertThat(column(sheet, 0)).containsExactly("T-2", "T-1");
  }

  @Test
  @DisplayName("an anonymous review never exports a foreign author's real name (ADR-0038)")
  void anonymousReviewExportsPseudonyms() throws Exception {
    seedDocument(true);
    annotate(AUDITOR_ID, 0, 0.1, 0.1, "From a peer");

    // MEMBER2 is a peer participant: to them the auditor is "Participant N".
    Sheet sheet = download(MEMBER2_ID);

    assertThat(column(sheet, 6)).singleElement().asString().startsWith("Participant");
    assertThat(column(sheet, 6)).noneMatch(name -> name.contains("Avery"));
  }

  @Test
  @DisplayName("a user who cannot see the review cannot export it")
  void foreignUserIsRefused() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "Private to this review");

    // EXTERNAL is neither owner nor participant — and, unlike the admins, has no
    // blanket visibility. The review answers 404 rather than 403 on purpose
    // (anti-enumeration: a stranger cannot tell an inaccessible review from a
    // non-existent one), which is exactly what listAnnotations does.
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations/export"), EXTERNAL_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("the wizard's field selection decides the columns, in the fixed order")
  void selectedFieldsDecideTheColumns() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "Only a few columns wanted");

    // Deliberately requested out of order — the sheet's column order is the
    // server's, so two exports of the same review stay comparable.
    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("logo", "false")
                        .param("fields", "status")
                        .param("fields", "taskKey"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    Sheet sheet = new XSSFWorkbook(new ByteArrayInputStream(body)).getSheetAt(0);

    Row header = sheet.getRow(0);
    assertThat(header.getCell(0).getStringCellValue()).isEqualTo("#");
    assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Status");
    assertThat(header.getCell(2)).isNull();
  }

  @Test
  @DisplayName("Replies counts answers, not the annotation's own opening comment")
  void repliesExcludeTheOpeningComment() throws Exception {
    seedDocument(false);
    String quiet = createAnnotationReturningId(MEMBER_ID, 0, 0.1, 0.1, "Nobody answered this");
    String discussed = createAnnotationReturningId(MEMBER_ID, 0, 0.1, 0.5, "This got answers");
    for (String answer : List.of("First answer", "Second answer")) {
      reply(discussed, AUDITOR_ID, answer);
    }
    assertThat(quiet).isNotBlank();

    Sheet sheet = download(MEMBER_ID);

    // The opening comment IS the annotation, so an unanswered one reads 0 —
    // not 1, which is what the raw commentCount would have shown.
    assertThat(sheet.getRow(0).getCell(7).getStringCellValue()).isEqualTo("Replies");
    assertThat(column(sheet, 7)).containsExactly("0", "2");
  }

  @Test
  @DisplayName("an unknown field is ignored rather than failing the export")
  void unknownFieldsAreIgnored() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "Still exports");

    // A client one release ahead must get a file, not a 400.
    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("logo", "false")
                        .param("fields", "status")
                        .param("fields", "somethingNew"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    Sheet sheet = new XSSFWorkbook(new ByteArrayInputStream(body)).getSheetAt(0);

    assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Status");
  }

  @Test
  @DisplayName("the scope narrows the rows, and task keys keep their whole-review numbering")
  void scopeNarrowsTheRows() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "Stays open");
    String resolvedId = createAnnotationReturningId(MEMBER_ID, 0, 0.1, 0.5, "Gets resolved");
    mockMvc
        .perform(as(post("/api/v1/annotations/" + resolvedId + "/resolve"), MEMBER_ID))
        .andExpect(status().isOk());

    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("logo", "false")
                        .param("scope", "resolved"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    Sheet sheet = new XSSFWorkbook(new ByteArrayInputStream(body)).getSheetAt(0);

    assertThat(column(sheet, 5)).containsExactly("Gets resolved");
    // T-2, not T-1: the key numbers the review, not the filtered slice, so it
    // still matches what the board shows.
    assertThat(column(sheet, 0)).containsExactly("T-2");
  }

  @Test
  @DisplayName("the comment sheet carries every comment's text under its annotation's task key")
  void commentSheetCarriesTheThreads() throws Exception {
    seedDocument(false);
    String first = createAnnotationReturningId(MEMBER_ID, 0, 0.1, 0.1, "The opening remark");
    String second = createAnnotationReturningId(MEMBER_ID, 0, 0.1, 0.5, "A second finding");
    reply(first, AUDITOR_ID, "Answering the first");
    reply(first, MEMBER_ID, "And once more");

    Sheet sheet = downloadComments(MEMBER_ID);

    Row header = sheet.getRow(0);
    assertThat(header.getCell(0).getStringCellValue()).isEqualTo("#");
    assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Comment");
    // Replies only. The opening comment IS the annotation — it is the Summary
    // column on the first sheet, and repeating it here contradicts the Replies
    // count beside it, which has always excluded it. An annotation nobody
    // answered therefore contributes no row at all.
    assertThat(column(sheet, 3)).containsExactly("Answering the first", "And once more");
    assertThat(column(sheet, 0)).containsExactly("T-1", "T-1");
    assertThat(second).isNotBlank();
  }

  @Test
  @DisplayName("the comment sheet is left out unless it was asked for")
  void commentSheetIsOptional() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "No second sheet wanted");

    // It costs a query round per annotation, so a plain export must not pay it.
    assertThat(download(MEMBER_ID).getWorkbook().getSheet("Comments")).isNull();
  }

  @Test
  @DisplayName("an anonymous review pseudonymises the comment sheet's authors too (ADR-0038)")
  void anonymousReviewPseudonymisesComments() throws Exception {
    seedDocument(true);
    String annotationId = createAnnotationReturningId(AUDITOR_ID, 0, 0.1, 0.1, "Opened by a peer");
    reply(annotationId, MEMBER_ID, "The owner answers");
    reply(annotationId, AUDITOR_ID, "And the peer again");

    // MEMBER2 is a peer participant: the second sheet must not become the hole
    // through which a foreign reviewer's real name leaks out of the review.
    List<String> authors = column(downloadComments(MEMBER2_ID), 1);

    // Two rows, not three: the sheet lists replies, and the opening comment is
    // the annotation itself.
    assertThat(authors).hasSize(2);
    // MEMBER owns the document, and the owner is the one identity anonymity does
    // not cover — it is their review, and everybody already knows that.
    assertThat(authors.get(0)).doesNotStartWith("Participant");
    assertThat(authors.get(1)).startsWith("Participant");
  }

  @Test
  @DisplayName("the Word report carries the same annotations, in the same reading order")
  void docxMatchesTheSpreadsheetsContent() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 1, 0.1, 0.1, "Second page note");
    annotate(MEMBER_ID, 0, 0.1, 0.1, "Page one, top");
    annotate(MEMBER_ID, 0, 0.1, 0.6, "Page one, lower");

    List<String> report = downloadDocx(MEMBER_ID);

    // Reading order, not creation order — the same rule the sheet follows.
    assertThat(report).containsSubsequence("Page one, top", "Page one, lower", "Second page note");
    // Task keys still number the review in CREATION order, so the first-created
    // annotation keeps T-1 even though it sorts last.
    assertThat(report).containsSubsequence("T-2 · Page 1", "T-3 · Page 1", "T-1 · Page 2");
    assertThat(report).first().asString().isEqualTo("Vendor agreement");
  }

  @Test
  @DisplayName("an anonymous review's Word report names no foreign author (ADR-0038)")
  void docxRespectsAnonymity() throws Exception {
    seedDocument(true);
    String annotationId = createAnnotationReturningId(AUDITOR_ID, 0, 0.1, 0.1, "Opened by a peer");
    reply(annotationId, AUDITOR_ID, "And answered by the same peer");

    // The report is the format most likely to be forwarded outside qnop, so the
    // pseudonyms matter here at least as much as in the spreadsheet.
    List<String> report = downloadDocx(MEMBER2_ID, "comments", "true");

    assertThat(report).anySatisfy(line -> assertThat(line).contains("Participant"));
    assertThat(report).noneSatisfy(line -> assertThat(line).contains("Avery Auditor"));
    assertThat(report).contains("Opened by a peer", "And answered by the same peer");
  }

  @Test
  @DisplayName("a user who cannot see the review cannot export it as Word either")
  void docxIsRefusedToForeignUsers() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "Not for outsiders");

    // 404 rather than 403: a refusal that distinguishes the two would confirm
    // the review exists.
    mockMvc
        .perform(
            as(
                get("/api/v1/documents/" + documentId + "/annotations/export")
                    .param("format", "docx"),
                EXTERNAL_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("an empty review still yields a well-formed Word document")
  void docxHandlesTheEmptyReview() throws Exception {
    seedDocument(false);

    List<String> report = downloadDocx(MEMBER_ID);

    assertThat(report).first().asString().isEqualTo("Vendor agreement");
    assertThat(report).contains("This review has no annotations.");
  }

  @Test
  @DisplayName("an unknown format falls back to the spreadsheet rather than failing")
  void unknownFormatFallsBackToXlsx() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "Still exports");

    // Same reasoning as unknown fields: a client one release ahead should get a
    // file it can open, not a 400. (PDF used to stand in for "unknown" here and no
    // longer can — it is a format this server knows, and may even be able to make.)
    mockMvc
        .perform(
            as(
                get("/api/v1/documents/" + documentId + "/annotations/export")
                    .param("format", "somethingNew"),
                MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Type",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
  }

  @Test
  @DisplayName("the Word report carries the branding logo in its page header")
  void docxCarriesTheBrandingLogo() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "A finding");

    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("format", "docx"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(body))) {
      // The whole chain, which only an IT covers: the bundled SVG default is
      // rasterized by the branding service and embedded by the renderer. Word
      // cannot embed SVG, so a header picture here means the conversion ran.
      assertThat(document.getHeaderList()).isNotEmpty();
      assertThat(document.getHeaderList().getFirst().getAllPictures()).isNotEmpty();
    }
  }

  @Test
  @DisplayName("the workbook's logo heads the sheet without covering any data")
  void workbookCarriesTheLogoAboveTheGrid() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "A finding");

    byte[] body =
        mockMvc
            .perform(as(get("/api/v1/documents/" + documentId + "/annotations/export"), MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    XSSFWorkbook branded = new XSSFWorkbook(new ByteArrayInputStream(body));
    Sheet sheet = branded.getSheetAt(0);

    // A band of its own: the picture is there, the row above the header is empty
    // and tall enough to hold it, and no cell is hidden underneath.
    assertThat(branded.getAllPictures()).isNotEmpty();
    assertThat(sheet.getRow(0).getCell(0)).isNull();
    assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("#");
    assertThat(sheet.getRow(2).getCell(5).getStringCellValue()).isEqualTo("A finding");
    assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 2);

    // And an unbranded export keeps the grid it had before branding existed.
    assertThat(download(MEMBER_ID).getRow(0).getCell(0).getStringCellValue()).isEqualTo("#");
  }

  @Test
  @DisplayName("the chosen date convention reaches the cells' display format")
  void dateFormatReachesTheCells() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "A finding");

    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("logo", "false")
                        .param("dateFormat", "european"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    Sheet sheet = new XSSFWorkbook(new ByteArrayInputStream(body)).getSheetAt(0);

    Cell created = sheet.getRow(1).getCell(9);
    // The cell still holds a real date — only its display changed, which is what
    // keeps Excel's own date filters and sorting working.
    assertThat(created.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
    assertThat(created.getCellStyle().getDataFormatString()).isEqualTo("dd.mm.yyyy hh:mm");
  }

  @Test
  @DisplayName("the requested timezone shifts the cells, and an unusable one falls back to UTC")
  void timezoneShiftsTheCells() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "A finding");

    double utc = createdCell(null).getNumericCellValue();
    double tokyo = createdCell("Asia/Tokyo").getNumericCellValue();
    double nonsense = createdCell("Middle/Earth").getNumericCellValue();

    // Excel dates are wall-clock and zone-less, so a different zone is literally
    // a different number in the cell — nine hours, in Tokyo's case.
    assertThat((tokyo - utc) * 24).isCloseTo(9.0, org.assertj.core.data.Offset.offset(0.01));
    // An id the JVM cannot resolve must not fail the download.
    assertThat(nonsense).isEqualTo(utc);
  }

  /** The Created cell of the single seeded annotation, exported in {@code timezone}. */
  private Cell createdCell(String timezone) throws Exception {
    var request =
        get("/api/v1/documents/" + documentId + "/annotations/export").param("logo", "false");
    if (timezone != null) {
      request = request.param("timezone", timezone);
    }
    byte[] body =
        mockMvc
            .perform(as(request, MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    return new XSSFWorkbook(new ByteArrayInputStream(body)).getSheetAt(0).getRow(1).getCell(9);
  }

  @Test
  @DisplayName("the download is named <slug>-annotations.<ext>, or whatever the user asked for")
  void filenameFollowsTheSlugUnlessOverridden() throws Exception {
    seedDocument(false);

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations/export"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("vendor-agreement-annotations.xlsx")));

    mockMvc
        .perform(
            as(
                get("/api/v1/documents/" + documentId + "/annotations/export")
                    .param("format", "docx")
                    .param("filename", "Q3 findings"),
                MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("Q3%20findings.docx")));
  }

  @Test
  @DisplayName("a filename cannot inject a header or escape the downloads folder")
  void filenameCannotInjectAHeader() throws Exception {
    seedDocument(false);

    String disposition =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("filename", "evil\r\nX-Injected: yes/../escape"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("Content-Disposition");

    // What matters is that the header cannot be ended early and the name cannot
    // address a directory: the words the user typed surviving as *text* inside a
    // filename is not a vulnerability, and stripping them would be theatre.
    assertThat(disposition).doesNotContain("\r", "\n", "/", "\\");
    assertThat(disposition.chars().noneMatch(Character::isISOControl)).isTrue();
    // The quoted token is not broken out of, and the file is still a workbook.
    assertThat(disposition).startsWith("attachment; filename=\"");
    assertThat(disposition).endsWith(".xlsx");
  }

  @Test
  @DisplayName("an image in a comment is embedded in the Word report")
  void docxEmbedsCommentImages() throws Exception {
    seedDocument(false);
    String url = uploadImage(MEMBER_ID, "screenshot.png");
    String annotationId = createAnnotationReturningId(MEMBER_ID, 0, 0.1, 0.1, "See the attachment");
    reply(annotationId, MEMBER_ID, "Here it is: ![screenshot.png](" + url + ")");

    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("format", "docx")
                        .param("comments", "true")
                        .param("logo", "false"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(body))) {
      // The whole chain: the URL is parsed out of the body, the attachment is
      // read through the participant-gated path, and the bytes land in the file.
      // Logo off, so the only picture here is the one from the comment.
      assertThat(document.getAllPictures()).hasSize(1);
    }
  }

  @Test
  @DisplayName("the spreadsheet names an image where it cannot show one")
  void workbookNamesCommentImages() throws Exception {
    seedDocument(false);
    String url = uploadImage(MEMBER_ID, "screenshot.png");
    createAnnotationReturningId(MEMBER_ID, 0, 0.1, 0.1, "Look: ![screenshot.png](" + url + ")");

    // A cell holds text, and a floating picture would detach from its row on the
    // first sort — but silence was the bug. The name sits on its own line because
    // an image is a block, the same reason Word gives it its own paragraph.
    assertThat(column(download(MEMBER_ID), 5))
        .singleElement()
        .asString()
        .isEqualTo("Look:\n[screenshot.png]");
  }

  @Test
  @DisplayName("an export never fetches an image belonging to another review")
  void doesNotFetchForeignAttachments() throws Exception {
    seedDocument(false);
    UUID ownDocument = documentId;
    String foreignUrl = uploadImage(MEMBER_ID, "foreign.png");

    // A second review, whose comment points at the FIRST review's attachment.
    seedDocument(false);
    createAnnotationReturningId(MEMBER_ID, 0, 0.1, 0.1, "Sneaky ![f.png](" + foreignUrl + ")");
    assertThat(foreignUrl).contains(ownDocument.toString());

    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("format", "docx")
                        .param("logo", "false"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(body))) {
      // Attachments are resolved only under the document being exported, so a
      // crafted URL cannot turn an export into a cross-review reader.
      assertThat(document.getAllPictures()).isEmpty();
      assertThat(document.getParagraphs().stream().map(XWPFParagraph::getText).toList())
          .contains("[f.png]");
    }
  }

  @Test
  @DisplayName("an attached file becomes a clickable row in the Word report")
  void docxLinksAttachedFiles() throws Exception {
    seedDocument(false);
    String json =
        mockMvc
            .perform(
                multipart("/api/v1/documents/" + documentId + "/attachments")
                    .file(
                        new MockMultipartFile(
                            "file", "notes.pdf", "application/pdf", "%PDF-1.4 body".getBytes()))
                    .header("Authorization", "Bearer " + token(MEMBER_ID)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url = com.jayway.jsonpath.JsonPath.read(json, "$.url");
    createAnnotationReturningId(MEMBER_ID, 0, 0.1, 0.1, "See [notes.pdf](" + url + ")");

    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("format", "docx")
                        .param("logo", "false"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(body))) {
      // Word cannot embed an arbitrary file, so the report links to it — and the
      // link has to survive the round trip as a real hyperlink relationship.
      //
      // Absolute, and asserted as such: a relative target is not a dead link,
      // Word resolves it against the document and produces file:///api/v1/…,
      // pointing at the reader's own disk.
      String attachmentId = url.substring(url.lastIndexOf('/') + 1);
      assertThat(document.getHyperlinks())
          .anySatisfy(
              link -> {
                // Absolute, or Word resolves it against the document and yields
                // file:///… on the reader's disk.
                assertThat(link.getURL()).startsWith("http");
                // The app's download page, not the bearer-authenticated API a
                // browser could never open by following a link.
                assertThat(link.getURL())
                    .endsWith("/attachments/" + documentId + "/" + attachmentId);
                assertThat(link.getURL()).doesNotContain("/api/v1/");
              });
      assertThat(document.getParagraphs().stream().map(XWPFParagraph::getText).toList())
          .anySatisfy(line -> assertThat(line).contains("notes.pdf", "PDF"));
    }
  }

  @Test
  @DisplayName("the workbook lists every upload on its own sheet, with a working link")
  void workbookLinksUploads() throws Exception {
    seedDocument(false);
    String imageUrl = uploadImage(MEMBER_ID, "screenshot.png");
    createAnnotationReturningId(
        MEMBER_ID, 0, 0.1, 0.1, "Look: ![screenshot.png](" + imageUrl + ")");

    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("logo", "false"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    Sheet sheet = new XSSFWorkbook(new ByteArrayInputStream(body)).getSheet("Attachments");

    assertThat(sheet).isNotNull();
    Cell cell = sheet.getRow(1).getCell(1);
    assertThat(cell.getStringCellValue()).isEqualTo("screenshot.png");
    // Same target as the Word report: the app's download page, absolute, never
    // the bearer-authenticated API a click could not open.
    assertThat(cell.getHyperlink().getAddress()).startsWith("http");
    assertThat(cell.getHyperlink().getAddress()).contains("/attachments/" + documentId + "/");
    assertThat(cell.getHyperlink().getAddress()).doesNotContain("/api/v1/");
  }

  @Test
  @DisplayName("an annotation longer than the list excerpt exports whole, images and all")
  void exportsBeyondTheListExcerpt() throws Exception {
    seedDocument(false);
    String url = uploadImage(MEMBER_ID, "late.png");
    // The list view carries only the first 300 characters of an opening comment,
    // for the annotation list in the UI. Anything past that — here an image, and
    // the sentence after it — used to be missing from the export, and a cut that
    // landed inside a link left a broken reference rather than a short one.
    String padding = "Ausfuehrliche Vorbemerkung. ".repeat(14);
    createAnnotationReturningId(
        MEMBER_ID, 0, 0.1, 0.1, padding + " ![late.png](" + url + ") Schlusssatz.");

    byte[] body =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents/" + documentId + "/annotations/export")
                        .param("format", "docx")
                        .param("logo", "false"),
                    MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(padding).hasSizeGreaterThan(300);
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(body))) {
      assertThat(document.getAllPictures()).hasSize(1);
      assertThat(document.getParagraphs().stream().map(XWPFParagraph::getText).toList())
          .anySatisfy(line -> assertThat(line).contains("Schlusssatz."));
    }
  }

  @Test
  @DisplayName("PDF is offered only where the server can produce it, and refused clearly otherwise")
  void pdfFollowsTheServersCapability() throws Exception {
    seedDocument(false);
    annotate(MEMBER_ID, 0, 0.1, 0.1, "A finding");

    String config =
        mockMvc
            .perform(as(get("/api/v1/config"), MEMBER_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> offered = com.jayway.jsonpath.JsonPath.read(config, "$.exportFormats");

    // The two formats that only assemble bytes are always there.
    assertThat(offered).contains("xlsx", "docx");

    var request =
        get("/api/v1/documents/" + documentId + "/annotations/export").param("format", "pdf");
    if (offered.contains("pdf")) {
      // An office converter is installed (CI images that ship one); the download works.
      mockMvc
          .perform(as(request, MEMBER_ID))
          .andExpect(status().isOk())
          .andExpect(header().string("Content-Type", "application/pdf"));
    } else {
      // The common case on a developer machine. Nothing broke and a retry will not
      // help, so it is 503 with a named code — never a 500.
      mockMvc
          .perform(as(request, MEMBER_ID))
          .andExpect(status().isServiceUnavailable())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                  "$.code", org.hamcrest.Matchers.is("EXPORT_FORMAT_UNAVAILABLE")));
    }
  }

  @Test
  @DisplayName("a review without annotations still yields a valid header-only workbook")
  void emptyReviewYieldsHeaderOnly() throws Exception {
    seedDocument(false);

    Sheet sheet = download(MEMBER_ID);

    assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("#");
    assertThat(sheet.getLastRowNum()).isZero();
  }
}
