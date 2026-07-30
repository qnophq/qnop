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
import io.qnop.entity.User;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.convert.OfficeConverter;
import io.qnop.service.job.JobQueuePoller;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
 * DOCX ingest end to end (issue #343, ADR-0010): a Word upload is converted out-of-process, the
 * conversion is what the viewer is served, and the upload itself stays downloadable.
 *
 * <p>Branches on whether an office converter is installed, the way the PDF export IT does. A
 * developer machine usually has none, and on such a server the correct behaviour is a refusal at
 * upload time — which is itself worth asserting.
 */
@AutoConfigureMockMvc
class DocxIngestIT extends AbstractIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository users;
  @Autowired DocumentRepository documents;
  @Autowired DocumentVersionRepository versions;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JobQueuePoller poller;
  @Autowired OfficeConverter converter;

  private final List<UUID> createdDocuments = new ArrayList<>();
  private final List<UUID> createdUsers = new ArrayList<>();

  @AfterEach
  void cleanup() {
    createdDocuments.forEach(id -> documents.findById(id).ifPresent(documents::delete));
    createdUsers.forEach(id -> users.findById(id).ifPresent(users::delete));
  }

  @Test
  @DisplayName("a Word upload is viewed through its conversion, and downloaded as itself")
  void wordIsConvertedForViewingAndKeptForDownload() throws Exception {
    UUID owner = createUser();
    byte[] docx = docx("Hello qnop from Word");

    if (!converter.isAvailable()) {
      // The common case on a developer machine. The answer is the same at every
      // later moment, so it is given now rather than by a job the user waits for.
      mockMvc
          .perform(
              multipart("/api/v1/documents")
                  .file(docxFile(docx))
                  .param("title", "Word IT")
                  .with(asUser(owner)))
          .andExpect(status().isUnsupportedMediaType());
      return;
    }

    MvcResult upload =
        mockMvc
            .perform(
                multipart("/api/v1/documents")
                    .file(docxFile(docx))
                    .param("title", "Word IT")
                    .with(asUser(owner)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.extractionStatus").value("PENDING"))
            .andReturn();
    UUID documentId = documentIdOf(upload);

    poller.poll();

    // The version records what was uploaded — not what it was converted into.
    mockMvc
        .perform(get("/api/v1/documents/{id}/versions", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versions[0].extractionStatus").value("READY"))
        .andExpect(
            jsonPath("$.versions[0].contentType")
                .value("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

    // The canonical model came out of the converted PDF, so anchoring, diff and the
    // viewer see exactly what they see for a native PDF — the point of ADR-0010.
    mockMvc
        .perform(get("/api/v1/documents/{id}/versions/1/rendered", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.surfaces.length()").value(1))
        .andExpect(jsonPath("$.surfaces[0].textSpans[0].text").value("Hello qnop from Word"));

    // The viewer is served a PDF...
    MvcResult rendition =
        mockMvc
            .perform(
                get("/api/v1/documents/{id}/versions/1/rendition", documentId).with(asUser(owner)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andReturn();
    assertThat(rendition.getResponse().getContentAsByteArray())
        .startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));

    // ...while a download still hands over the Word file that was uploaded.
    MvcResult original =
        mockMvc
            .perform(
                get("/api/v1/documents/{id}/versions/1/original", documentId).with(asUser(owner)))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("Word IT-v1.docx")))
            .andReturn();
    assertThat(original.getResponse().getContentAsByteArray()).isEqualTo(docx);

    // Two objects, both referenced: the second is what the purge and the
    // consistency scan have to know about (issue #343).
    UUID versionId =
        versions.findByDocumentIdOrderByVersionNumberAsc(documentId).getFirst().getId();
    assertThat(versions.findById(versionId).orElseThrow().getRenditionStorageKey()).isNotNull();
  }

  @Test
  @DisplayName("an archive that is not a Word document is refused whatever it is called")
  void refusesArchivesThatAreNotWord() throws Exception {
    UUID owner = createUser();
    // A ZIP named .docx with a Word MIME type: everything a client controls says
    // Word, and none of it is trusted (issue #245).
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("xl/workbook.xml"));
      zip.write("<workbook/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(docxFile(out.toByteArray()))
                .param("title", "Not really Word")
                .with(asUser(owner)))
        .andExpect(status().isUnsupportedMediaType());
  }

  private UUID documentIdOf(MvcResult result) throws Exception {
    UUID id =
        UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.documentId"));
    createdDocuments.add(id);
    return id;
  }

  private UUID createUser() {
    String name = "docx-" + UUID.randomUUID();
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

  private static MockMultipartFile docxFile(byte[] bytes) {
    return new MockMultipartFile(
        "file",
        "doc.docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        bytes);
  }

  private static byte[] docx(String line) throws Exception {
    try (XWPFDocument document = new XWPFDocument()) {
      document.createParagraph().createRun().setText(line);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.write(out);
      return out.toByteArray();
    }
  }
}
