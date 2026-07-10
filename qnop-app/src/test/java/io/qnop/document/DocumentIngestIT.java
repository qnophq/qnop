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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.User;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.job.JobQueuePoller;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end ingest pipeline (issue #245, ADR-0032): multipart upload → transactional version +
 * extraction job → durable job run → rendered representation + original binary served. Not
 * {@code @Transactional}: the ingest commits via its own TransactionTemplate and the job poller
 * runs against committed state; cleanup happens in {@link #cleanup()}. Requires Docker.
 */
@AutoConfigureMockMvc
class DocumentIngestIT extends AbstractIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository users;
  @Autowired DocumentRepository documents;
  @Autowired ReviewParticipantRepository participants;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JobQueuePoller poller;
  @Autowired ApplicationSettingsService settings;

  private final List<UUID> createdDocuments = new ArrayList<>();
  private final List<UUID> createdUsers = new ArrayList<>();

  @AfterEach
  void cleanup() {
    // Documents cascade their versions/participants; owner FK is RESTRICT → docs first.
    createdDocuments.forEach(id -> documents.findById(id).ifPresent(documents::delete));
    createdUsers.forEach(id -> users.findById(id).ifPresent(users::delete));
  }

  @Test
  @DisplayName("upload → extraction job → READY: rendered representation and original are served")
  void uploadExtractServeRoundTrip() throws Exception {
    UUID owner = createUser();
    byte[] pdf = pdf("Hello qnop ingest");

    // 1) Upload creates the document + v1, PENDING.
    MvcResult upload =
        mockMvc
            .perform(
                multipart("/api/v1/documents")
                    .file(pdfFile(pdf))
                    .param("title", "Ingest IT")
                    .with(asUser(owner)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.versionNumber").value(1))
            .andExpect(jsonPath("$.extractionStatus").value("PENDING"))
            .andReturn();
    UUID documentId = documentIdOf(upload);

    // 2) The transactionally-enqueued job runs and attaches the rendering.
    poller.poll();

    mockMvc
        .perform(get("/api/v1/documents/{id}/versions", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versions[0].extractionStatus").value("READY"))
        .andExpect(jsonPath("$.versions[0].contentType").value("application/pdf"));

    // 3) The rendered representation matches the published contract.
    mockMvc
        .perform(get("/api/v1/documents/{id}/versions/1/rendered", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.surfaces.length()").value(1))
        .andExpect(jsonPath("$.surfaces[0].width").value((double) PDRectangle.LETTER.getWidth()))
        .andExpect(jsonPath("$.surfaces[0].textSpans[0].text").value("Hello qnop ingest"))
        .andExpect(jsonPath("$.surfaces[0].textSpans[0].startOffset").value(0))
        .andExpect(jsonPath("$.surfaces[0].textSpans[0].box.x").isNumber());

    // 4) The original streams back byte-identical, ETagged with the content hash.
    MvcResult original =
        mockMvc
            .perform(
                get("/api/v1/documents/{id}/versions/1/original", documentId).with(asUser(owner)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            // Filename extension is derived from the content type, not hardcoded (issue #328).
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("Ingest IT-v1.pdf")))
            .andExpect(header().exists("ETag"))
            .andReturn();
    assertThat(original.getResponse().getContentAsByteArray()).isEqualTo(pdf);

    // 5) Document metadata reflects the latest version.
    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Ingest IT"))
        .andExpect(jsonPath("$.latestVersionNumber").value(1))
        // Defaults to a non-anonymous review (issue #413).
        .andExpect(jsonPath("$.anonymous").value(false));
  }

  @Test
  @DisplayName("the anonymous form field is persisted and echoed on the document metadata (#413)")
  void createRespectsTheAnonymousFlag() throws Exception {
    UUID owner = createUser();

    MvcResult upload =
        mockMvc
            .perform(
                multipart("/api/v1/documents")
                    .file(pdfFile(pdf("anon")))
                    .param("title", "Anon review")
                    .param("anonymous", "true")
                    .with(asUser(owner)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID documentId = documentIdOf(upload);

    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.anonymous").value(true));
  }

  @Test
  @DisplayName("re-upload is owner-only: owner appends v2, stranger gets 404, participant 403")
  void addVersionIsOwnerOnly() throws Exception {
    UUID owner = createUser();
    UUID participant = createUser();
    UUID stranger = createUser();
    UUID documentId = uploadDocument(owner, pdf("v1"));
    participants.save(ReviewParticipant.forUser(documentId, participant));

    mockMvc
        .perform(
            multipart("/api/v1/documents/{id}/versions", documentId)
                .file(pdfFile(pdf("v2")))
                .with(asUser(owner)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.versionNumber").value(2));

    // Anti-enumeration: a stranger cannot learn the document exists.
    mockMvc
        .perform(
            multipart("/api/v1/documents/{id}/versions", documentId)
                .file(pdfFile(pdf("v3")))
                .with(asUser(stranger)))
        .andExpect(status().isNotFound());

    // A participant sees the document but re-upload stays owner-only.
    mockMvc
        .perform(
            multipart("/api/v1/documents/{id}/versions", documentId)
                .file(pdfFile(pdf("v3")))
                .with(asUser(participant)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_OWNER"));
  }

  @Test
  @DisplayName("a non-PDF upload is rejected with 415 and the uniform envelope")
  void rejectsNonPdf() throws Exception {
    UUID owner = createUser();

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(
                    new MockMultipartFile(
                        "file", "evil.pdf", "application/pdf", "MZ not a pdf".getBytes()))
                .param("title", "Nope")
                .with(asUser(owner)))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  @DisplayName("an empty upload is rejected with 400 and the uniform envelope")
  void rejectsEmptyUpload() throws Exception {
    UUID owner = createUser();

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]))
                .param("title", "Empty")
                .with(asUser(owner)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("an upload over the configured size cap is rejected with 413 (before staging)")
  void rejectsOversizeUpload() throws Exception {
    UUID owner = createUser();
    int originalCapMb = settings.getInteger(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB);
    try {
      settings.update(
          Map.of(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB.getKey(), "1"), null);
      byte[] tooBig = new byte[2 * 1024 * 1024]; // 2 MB > 1 MB cap
      System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, tooBig, 0, 5);

      mockMvc
          .perform(
              multipart("/api/v1/documents")
                  .file(pdfFile(tooBig))
                  .param("title", "Too big")
                  .with(asUser(owner)))
          .andExpect(status().isPayloadTooLarge())
          .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    } finally {
      settings.update(
          Map.of(
              ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB.getKey(),
              String.valueOf(originalCapMb)),
          null);
    }
  }

  @Test
  @DisplayName("a corrupt PDF uploads fine but the extraction job marks the version FAILED")
  void corruptPdfEndsFailed() throws Exception {
    UUID owner = createUser();
    byte[] corrupt = "%PDF-1.7 garbage that PDFBox cannot parse".getBytes();
    UUID documentId = uploadDocument(owner, corrupt);

    poller.poll();

    mockMvc
        .perform(get("/api/v1/documents/{id}/versions", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versions[0].extractionStatus").value("FAILED"));
    mockMvc
        .perform(get("/api/v1/documents/{id}/versions/1/rendered", documentId).with(asUser(owner)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXTRACTION_FAILED"));
  }

  @Test
  @DisplayName("the rendered representation is 409 EXTRACTION_PENDING before the job has run")
  void renderedIsPendingBeforeJobRuns() throws Exception {
    UUID owner = createUser();
    UUID documentId = uploadDocument(owner, pdf("not yet extracted"));

    mockMvc
        .perform(get("/api/v1/documents/{id}/versions/1/rendered", documentId).with(asUser(owner)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXTRACTION_PENDING"));
  }

  // --- helpers ---------------------------------------------------------------

  private UUID uploadDocument(UUID owner, byte[] pdf) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                multipart("/api/v1/documents")
                    .file(pdfFile(pdf))
                    .param("title", "IT doc")
                    .with(asUser(owner)))
            .andExpect(status().isCreated())
            .andReturn();
    return documentIdOf(result);
  }

  private UUID documentIdOf(MvcResult result) throws Exception {
    UUID id =
        UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.documentId"));
    createdDocuments.add(id);
    return id;
  }

  private UUID createUser() {
    String name = "doc-" + UUID.randomUUID();
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
