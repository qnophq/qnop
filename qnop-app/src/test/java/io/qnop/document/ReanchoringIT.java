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
package io.qnop.document;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.job.JobQueuePoller;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end re-anchoring (issue #248, ADR-0009): upload v1 → annotate through the API → upload
 * further versions → the extraction job chains the re-anchoring job on the durable queue → the
 * annotation's placement on each new version resolves to PLACED / ORPHANED and orphans are
 * queryable via the {@code placementStatus} filter. Tests drive the queue with {@code
 * poller.poll()} (the scheduled tick is disabled in ITs). Requires Docker.
 */
@AutoConfigureMockMvc
class ReanchoringIT extends AbstractIntegrationTest {

  private static final String QUOTE = "the payment terms are thirty days";

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository users;
  @Autowired DocumentRepository documents;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JobQueuePoller poller;

  private final List<UUID> createdDocuments = new ArrayList<>();
  private final List<UUID> createdUsers = new ArrayList<>();

  @AfterEach
  void cleanup() {
    createdDocuments.forEach(id -> documents.findById(id).ifPresent(documents::delete));
    createdUsers.forEach(id -> users.findById(id).ifPresent(users::delete));
  }

  @Test
  @DisplayName("a RESOLVED annotation follows the document onto the new version (#403 regression)")
  void resolvedAnnotationsAreReanchoredToo() throws Exception {
    UUID owner = createUser();
    UUID documentId =
        upload(owner, "whereas " + QUOTE + " from invoice", "a second unrelated line");
    poller.poll(); // extract v1

    String created =
        mockMvc
            .perform(
                post("/api/v1/documents/{id}/annotations", documentId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(annotationBody())
                    .with(asUser(owner)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String annotationId = JsonPath.read(created, "$.id");

    // The author settles the concern BEFORE the new version arrives — since
    // #405 that is not terminal (the thread stays a record, reopen exists),
    // so the mark must still follow the document.
    mockMvc
        .perform(post("/api/v1/annotations/{id}/resolve", annotationId).with(asUser(owner)))
        .andExpect(status().isOk());

    addVersion(owner, documentId, "a brand new intro line", "whereas " + QUOTE + " from invoice");
    poller.poll(); // extract v2 → chains the re-anchor job
    poller.poll(); // run the re-anchor job

    mockMvc
        .perform(
            get("/api/v1/documents/{id}/annotations", documentId)
                .param("version", "2")
                .with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].status").value("RESOLVED"))
        .andExpect(jsonPath("$.annotations[0].placementStatus").value("PLACED"));
  }

  @Test
  @DisplayName("a new version re-places the annotation; a version without the text orphans it")
  void reanchorsAcrossVersions() throws Exception {
    UUID owner = createUser();

    // v1: upload + extract, then annotate the quote through the API.
    UUID documentId =
        upload(owner, "whereas " + QUOTE + " from invoice", "a second unrelated line");
    poller.poll(); // extract v1 (no placements yet → no re-anchor job)

    mockMvc
        .perform(
            post("/api/v1/documents/{id}/annotations", documentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(annotationBody())
                .with(asUser(owner)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.placementStatus").value("PLACED"));

    // v2 still contains the quote (shifted by a new first line) → PLACED again.
    addVersion(owner, documentId, "a brand new intro line", "whereas " + QUOTE + " from invoice");
    mockMvc
        .perform(
            get("/api/v1/documents/{id}/annotations", documentId)
                .param("version", "2")
                .with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].placementStatus").value("PENDING"));

    poller.poll(); // extract v2 → chains the re-anchor job
    poller.poll(); // run the re-anchor job

    mockMvc
        .perform(
            get("/api/v1/documents/{id}/annotations", documentId)
                .param("version", "2")
                .with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].placementStatus").value("PLACED"));

    // v3 drops the quote entirely → ORPHANED, and the filter surfaces it.
    addVersion(owner, documentId, "every clause was rewritten from scratch in this draft");
    poller.poll(); // extract v3
    poller.poll(); // re-anchor v3

    mockMvc
        .perform(
            get("/api/v1/documents/{id}/annotations", documentId)
                .param("version", "3")
                .param("placementStatus", "ORPHANED")
                .with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations.length()").value(1))
        .andExpect(jsonPath("$.annotations[0].placementStatus").value("ORPHANED"));

    // The same filter finds nothing PLACED on v3 (proof the filter actually narrows).
    mockMvc
        .perform(
            get("/api/v1/documents/{id}/annotations", documentId)
                .param("version", "3")
                .param("placementStatus", "PLACED")
                .with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations.length()").value(0));
  }

  @Test
  @DisplayName("the placementStatus filter without a version is rejected with 400")
  void filterWithoutVersionIs400() throws Exception {
    UUID owner = createUser();
    UUID documentId = upload(owner, "some line");

    mockMvc
        .perform(
            get("/api/v1/documents/{id}/annotations", documentId)
                .param("placementStatus", "ORPHANED")
                .with(asUser(owner)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  // --- helpers ---------------------------------------------------------------

  /** The annotation drawn on v1: region + the quote with its real prefix/suffix context. */
  private static String annotationBody() {
    return """
        {"versionNumber":1,"anchor":{
          "region":{"surfaceIndex":0,"box":{"x":0.1,"y":0.1,"width":0.5,"height":0.05}},
          "textQuote":{"quote":"%s","prefix":"whereas ","suffix":" from invoice"},
          "textPosition":{"start":8,"end":%d}},
         "comment":"please double-check this clause"}
        """
        .formatted(QUOTE, 8 + QUOTE.length());
  }

  private UUID upload(UUID owner, String... lines) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                multipart("/api/v1/documents")
                    .file(pdfFile(pdf(lines)))
                    .param("title", "Reanchor IT")
                    .with(asUser(owner)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID id =
        UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.documentId"));
    createdDocuments.add(id);
    return id;
  }

  private void addVersion(UUID owner, UUID documentId, String... lines) throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/documents/{id}/versions", documentId)
                .file(pdfFile(pdf(lines)))
                .with(asUser(owner)))
        .andExpect(status().isCreated());
  }

  private UUID createUser() {
    String name = "reanchor-" + UUID.randomUUID();
    User user =
        User.internal(name, name + "@example.com", name, passwordEncoder.encode("irrelevant-pw"));
    user.setEnabled(true);
    UUID id = users.saveAndFlush(user).getId();
    createdUsers.add(id);
    return id;
  }

  private static RequestPostProcessor asUser(UUID userId) {
    return jwt().jwt(j -> j.subject(userId.toString()));
  }

  private static MockMultipartFile pdfFile(byte[] bytes) {
    return new MockMultipartFile("file", "doc.pdf", "application/pdf", bytes);
  }

  /** One LETTER page with one text line per given string. */
  private static byte[] pdf(String... lines) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        float y = 700;
        for (String line : lines) {
          content.beginText();
          content.newLineAtOffset(72, y);
          content.showText(line);
          content.endText();
          y -= 20;
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }
}
