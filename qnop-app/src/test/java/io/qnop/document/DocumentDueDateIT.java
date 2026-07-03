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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.Document;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.User;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.UserRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The optional review due date (issue #295): settable on multipart create (future-only), owner-only
 * editable/clearable afterwards via {@code PATCH /documents/{id}}, surfaced on the summary and the
 * document response, and audited on change. Mirrors {@link DocumentIngestIT}'s ingest-through-HTTP
 * style; not {@code @Transactional} because the ingest commits via its own TransactionTemplate.
 * Requires Docker.
 */
@AutoConfigureMockMvc
class DocumentDueDateIT extends AbstractIntegrationTest {

  private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");
  private static final Instant OTHER_FUTURE = Instant.parse("2099-06-15T12:00:00Z");
  private static final Instant PAST = Instant.parse("2000-01-01T00:00:00Z");

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository users;
  @Autowired DocumentRepository documents;
  @Autowired ReviewParticipantRepository participants;
  @Autowired AuditEventRepository auditEvents;
  @Autowired PasswordEncoder passwordEncoder;

  private final List<UUID> createdDocuments = new ArrayList<>();
  private final List<UUID> createdUsers = new ArrayList<>();

  @AfterEach
  void cleanup() {
    createdDocuments.forEach(id -> documents.findById(id).ifPresent(documents::delete));
    createdUsers.forEach(id -> users.findById(id).ifPresent(users::delete));
  }

  @Test
  @DisplayName("a future due date set at create is stored and surfaced on the document + summary")
  void createWithFutureDueDateStoresIt() throws Exception {
    UUID owner = createUser();

    UUID documentId = uploadDocument(owner, FUTURE.toString());

    assertThat(documents.findById(documentId).map(Document::getDueAt)).contains(FUTURE);
    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dueAt").exists());
    mockMvc
        .perform(get("/api/v1/documents").with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].dueAt").exists());
  }

  @Test
  @DisplayName("a past due date at create is rejected with 400 and no document is created")
  void createWithPastDueDateIsRejected() throws Exception {
    UUID owner = createUser();

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(pdfFile(pdf("past due")))
                .param("title", "Past due")
                .param("dueAt", PAST.toString())
                .with(asUser(owner)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("the owner edits the due date; the change is persisted and audited")
  void ownerEditsDueDateAndAuditEventAppended() throws Exception {
    UUID owner = createUser();
    UUID documentId = uploadDocument(owner, FUTURE.toString());

    mockMvc
        .perform(
            patch("/api/v1/documents/{id}", documentId)
                .with(asUser(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"" + OTHER_FUTURE + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dueAt").exists());

    assertThat(documents.findById(documentId).map(Document::getDueAt)).contains(OTHER_FUTURE);
    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(documentId))
        .anyMatch(e -> "document.due_date.changed".equals(e.getEventType()));
  }

  @Test
  @DisplayName("editing accepts a past due date so the owner can correct it after the fact")
  void editAcceptsPastDueDate() throws Exception {
    UUID owner = createUser();
    UUID documentId = uploadDocument(owner, FUTURE.toString());

    mockMvc
        .perform(
            patch("/api/v1/documents/{id}", documentId)
                .with(asUser(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"" + PAST + "\"}"))
        .andExpect(status().isOk());

    assertThat(documents.findById(documentId).map(Document::getDueAt)).contains(PAST);
  }

  @Test
  @DisplayName("an explicit null clears the due date")
  void ownerClearsDueDate() throws Exception {
    UUID owner = createUser();
    UUID documentId = uploadDocument(owner, FUTURE.toString());

    mockMvc
        .perform(
            patch("/api/v1/documents/{id}", documentId)
                .with(asUser(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":null}"))
        .andExpect(status().isOk());

    assertThat(documents.findById(documentId).orElseThrow().getDueAt()).isNull();
  }

  @Test
  @DisplayName("editing the due date is owner-only: stranger 404, participant 403 NOT_OWNER")
  void updateDueDateIsOwnerOnly() throws Exception {
    UUID owner = createUser();
    UUID participant = createUser();
    UUID stranger = createUser();
    UUID documentId = uploadDocument(owner, null);
    participants.save(ReviewParticipant.forUser(documentId, participant));

    String body = "{\"dueAt\":\"" + FUTURE + "\"}";

    // Anti-enumeration: a stranger cannot learn the document exists.
    mockMvc
        .perform(
            patch("/api/v1/documents/{id}", documentId)
                .with(asUser(stranger))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());

    // A participant sees the document but editing stays owner-only.
    mockMvc
        .perform(
            patch("/api/v1/documents/{id}", documentId)
                .with(asUser(participant))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_OWNER"));

    assertThat(documents.findById(documentId).orElseThrow().getDueAt()).isNull();
  }

  // --- helpers ---------------------------------------------------------------

  /** Uploads a document owned by {@code owner}; {@code dueAt} is an ISO instant or null. */
  private UUID uploadDocument(UUID owner, String dueAt) throws Exception {
    var request =
        multipart("/api/v1/documents")
            .file(pdfFile(pdf("due-date IT")))
            .param("title", "Due IT")
            .with(asUser(owner));
    if (dueAt != null) {
      request = request.param("dueAt", dueAt);
    }
    MvcResult result = mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
    UUID id =
        UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.documentId"));
    createdDocuments.add(id);
    return id;
  }

  private UUID createUser() {
    String name = "due-" + UUID.randomUUID();
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

  private static org.springframework.mock.web.MockMultipartFile pdfFile(byte[] bytes) {
    return new org.springframework.mock.web.MockMultipartFile(
        "file", "doc.pdf", "application/pdf", bytes);
  }

  private static byte[] pdf(String line) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 700);
        content.showText(line);
        content.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }
}
