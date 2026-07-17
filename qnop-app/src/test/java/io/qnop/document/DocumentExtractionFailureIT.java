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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.User;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
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
 * The downstream cascade of a FAILED extraction (issue #351). {@code DocumentIngestIT} already
 * proves a corrupt upload lands {@code extractionStatus FAILED} and its {@code /rendered}
 * representation answers {@code 409 EXTRACTION_FAILED}. This IT covers the rest of the cascade:
 *
 * <ul>
 *   <li>a version diff that touches a FAILED version is refused with {@code 409 EXTRACTION_FAILED}
 *       (the diff needs the extracted text of both sides, exactly like {@code /rendered});
 *   <li>re-anchoring never runs against a FAILED version — the extraction failure marks the
 *       version's pending placements {@code FAILED} instead of chaining a re-anchor job, so an
 *       annotation carried onto a corrupt new version rests at {@code FAILED}, never {@code
 *       PLACED}/{@code MOVED}/{@code ORPHANED}.
 * </ul>
 *
 * <p>Tests drive the durable job queue with {@code poller.poll()} (the scheduled tick is disabled
 * in ITs). Requires Docker (Testcontainers Postgres + MinIO).
 */
@AutoConfigureMockMvc
class DocumentExtractionFailureIT extends AbstractIntegrationTest {

  private static final String QUOTE = "the payment terms are thirty days";

  /** A PDF header the magic-byte check accepts, but PDFBox cannot parse — extraction FAILS. */
  private static final byte[] CORRUPT_PDF = "%PDF-1.7 garbage that PDFBox cannot parse".getBytes();

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository users;
  @Autowired DocumentRepository documents;
  @Autowired DocumentVersionRepository versions;
  @Autowired AuditEventRepository auditEvents;
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
  @DisplayName("a diff that touches a FAILED version is refused with 409 EXTRACTION_FAILED")
  void diffAgainstAFailedVersionIsConflict() throws Exception {
    UUID owner = createUser();
    UUID documentId = upload(owner, "the original clause stands");
    poller.poll(); // extract v1 → READY

    addVersion(owner, documentId, CORRUPT_PDF);
    poller.poll(); // extract v2 → FAILED (chains nothing)

    mockMvc
        .perform(get("/api/v1/documents/{id}/versions", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versions[0].extractionStatus").value("READY"))
        .andExpect(jsonPath("$.versions[1].extractionStatus").value("FAILED"));

    // v1 is READY, v2 is FAILED: the diff cannot render the FAILED side.
    mockMvc
        .perform(
            get("/api/v1/documents/{id}/diff", documentId)
                .param("from", "1")
                .param("to", "2")
                .with(asUser(owner)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXTRACTION_FAILED"));
  }

  @Test
  @DisplayName("re-anchoring skips a FAILED version, leaving the carried placement FAILED")
  void reanchorSkipsAFailedVersionLeavingThePlacementFailed() throws Exception {
    UUID owner = createUser();
    UUID documentId = upload(owner, "whereas " + QUOTE + " from invoice", "a second line");
    poller.poll(); // extract v1 (no placements yet → no re-anchor job)

    mockMvc
        .perform(
            post("/api/v1/documents/{id}/annotations", documentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(annotationBody())
                .with(asUser(owner)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.placementStatus").value("PLACED"));

    // v2 is corrupt. Uploading it eagerly seeds a PENDING placement to be re-anchored...
    addVersion(owner, documentId, CORRUPT_PDF);
    mockMvc
        .perform(
            get("/api/v1/documents/{id}/annotations", documentId)
                .param("version", "2")
                .with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].placementStatus").value("PENDING"));

    // ...but v2's extraction FAILS, so the failure marks the placement FAILED and chains no
    // re-anchor job — the annotation is never re-placed onto the unreadable version.
    poller.poll(); // extract v2 → FAILED

    mockMvc
        .perform(get("/api/v1/documents/{id}/versions", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versions[1].extractionStatus").value("FAILED"));
    mockMvc
        .perform(
            get("/api/v1/documents/{id}/annotations", documentId)
                .param("version", "2")
                .with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].placementStatus").value("FAILED"));

    // No re-anchor job was chained: a further poll is a no-op and the placement stays FAILED.
    poller.poll();
    mockMvc
        .perform(
            get("/api/v1/documents/{id}/annotations", documentId)
                .param("version", "2")
                .with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].placementStatus").value("FAILED"));
  }

  @Test
  @DisplayName(
      "extraction audits success and failure, and persists the failure reason (issue #325)")
  void auditsOutcomesAndPersistsFailureReason() throws Exception {
    UUID owner = createUser();
    UUID documentId = upload(owner, "the original clause stands");
    poller.poll(); // extract v1 → READY

    addVersion(owner, documentId, CORRUPT_PDF);
    poller.poll(); // extract v2 → FAILED

    List<DocumentVersion> chain = versions.findByDocumentIdOrderByVersionNumberAsc(documentId);
    UUID v1 = chain.get(0).getId();
    DocumentVersion v2 = chain.get(1);

    // The failure reason is persisted for operators — the corrupt PDF surfaces PDFBox's message.
    assertThat(v2.getExtractionFailureReason()).contains("Unreadable PDF");

    List<AuditEvent> trail = auditEvents.findByDocumentIdOrderByCreatedAtDesc(documentId);
    // v1's extraction succeeded — system-generated (no actor), detail names the version.
    AuditEvent succeeded = onlyEvent(trail, "extraction.succeeded");
    assertThat(succeeded.getActorId()).isNull();
    assertThat(succeeded.getDetail()).contains(v1.toString());
    // v2's extraction failed — detail names the version and carries the reason.
    AuditEvent failed = onlyEvent(trail, "extraction.failed");
    assertThat(failed.getActorId()).isNull();
    assertThat(failed.getDetail()).contains(v2.getId().toString()).contains("Unreadable PDF");
  }

  // --- helpers ---------------------------------------------------------------

  /** The single audit event of the given type in the trail (fails if absent or duplicated). */
  private static AuditEvent onlyEvent(List<AuditEvent> trail, String eventType) {
    List<AuditEvent> matches =
        trail.stream().filter(e -> e.getEventType().equals(eventType)).toList();
    assertThat(matches).as("exactly one %s event", eventType).hasSize(1);
    return matches.get(0);
  }

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
                    .param("title", "Extraction failure IT")
                    .with(asUser(owner)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID id =
        UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.documentId"));
    createdDocuments.add(id);
    return id;
  }

  private void addVersion(UUID owner, UUID documentId, byte[] bytes) throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/documents/{id}/versions", documentId)
                .file(pdfFile(bytes))
                .with(asUser(owner)))
        .andExpect(status().isCreated());
  }

  private UUID createUser() {
    String name = "extfail-" + UUID.randomUUID();
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
