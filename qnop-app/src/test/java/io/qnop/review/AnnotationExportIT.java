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
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
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

  private Sheet download(UUID actor) throws Exception {
    byte[] body =
        mockMvc
            .perform(as(get("/api/v1/documents/" + documentId + "/annotations/export"), actor))
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
  @DisplayName("a review without annotations still yields a valid header-only workbook")
  void emptyReviewYieldsHeaderOnly() throws Exception {
    seedDocument(false);

    Sheet sheet = download(MEMBER_ID);

    assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("#");
    assertThat(sheet.getLastRowNum()).isZero();
  }
}
