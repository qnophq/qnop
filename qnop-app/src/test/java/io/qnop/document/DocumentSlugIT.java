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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.UserRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Review slugs (issue #411): optional kebab-case identifier chosen at creation, unique ignoring
 * case, resolvable via {@code GET /documents/by-slug/{slug}} with the same anti-enumeration 404 as
 * the id route. Requires Docker.
 */
@AutoConfigureMockMvc
class DocumentSlugIT extends AbstractIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository users;
  @Autowired DocumentRepository documents;
  @Autowired PasswordEncoder passwordEncoder;

  private final List<UUID> createdDocuments = new ArrayList<>();
  private final List<UUID> createdUsers = new ArrayList<>();

  @AfterEach
  void cleanup() {
    createdDocuments.forEach(id -> documents.findById(id).ifPresent(documents::delete));
    createdUsers.forEach(id -> users.findById(id).ifPresent(users::delete));
  }

  @Test
  @DisplayName("create with slug: normalized, echoed on metadata, resolvable case-insensitively")
  void slugRoundTrip() throws Exception {
    UUID owner = createUser();

    // Mixed case + surrounding whitespace normalizes to the canonical lowercase form.
    UUID documentId = createDocument(owner, "  Q3-Contract-Review  ");

    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("q3-contract-review"))
        // The detail metadata carries the latest version's MIME type for the
        // typed document icon (issue #509 follow-up).
        .andExpect(jsonPath("$.contentType").value("application/pdf"));

    // Lookup ignores case too — and the metadata resolves the owner's identity
    // itself (structurally public, #472), never via the principal directory.
    mockMvc
        .perform(get("/api/v1/documents/by-slug/{slug}", "Q3-CONTRACT-REVIEW").with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(documentId.toString()))
        .andExpect(jsonPath("$.slug").value("q3-contract-review"))
        .andExpect(jsonPath("$.ownerDisplayName", org.hamcrest.Matchers.startsWith("slug-")));
  }

  @Test
  @DisplayName("a document without a slug answers null and is unaffected")
  void slugIsOptional() throws Exception {
    UUID owner = createUser();
    UUID documentId = createDocument(owner, null);

    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").doesNotExist());
  }

  @Test
  @DisplayName("taken slug → 409 SLUG_TAKEN with the slug field error, even as a case variant")
  void slugUniquenessIgnoresCase() throws Exception {
    UUID owner = createUser();
    createDocument(owner, "unique-review");

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(pdfFile(pdf("dup")))
                .param("title", "Duplicate slug")
                .param("slug", "UNIQUE-Review")
                .with(asUser(owner)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SLUG_TAKEN"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("slug"));
  }

  @Test
  @DisplayName("malformed and UUID-shaped slugs → 400 VALIDATION_ERROR on the slug field")
  void slugShapeIsValidated() throws Exception {
    UUID owner = createUser();

    for (String bad :
        new String[] {
          "has_underscore",
          "double--hyphen",
          "-leading",
          "trailing-",
          "ab", // below the 3-character minimum
          "x".repeat(65), // above the 64-character maximum
          UUID.randomUUID().toString() // UUID-shaped slugs would shadow id routes
        }) {
      mockMvc
          .perform(
              multipart("/api/v1/documents")
                  .file(pdfFile(pdf("bad slug")))
                  .param("title", "Bad slug")
                  .param("slug", bad)
                  .with(asUser(owner)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.fieldErrors[0].field").value("slug"));
    }
  }

  @Test
  @DisplayName("by-slug is as non-enumerable as by-id: stranger and unknown slug both get 404")
  void bySlugAntiEnumeration() throws Exception {
    UUID owner = createUser();
    UUID stranger = createUser();
    createDocument(owner, "hidden-review");

    mockMvc
        .perform(get("/api/v1/documents/by-slug/{slug}", "hidden-review").with(asUser(stranger)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    mockMvc
        .perform(get("/api/v1/documents/by-slug/{slug}", "no-such-slug").with(asUser(owner)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // --- helpers ---------------------------------------------------------------

  private UUID createDocument(UUID owner, String slug) throws Exception {
    var request =
        multipart("/api/v1/documents")
            .file(pdfFile(pdf("slug IT")))
            .param("title", "Slug IT")
            .with(asUser(owner));
    if (slug != null) {
      request = request.param("slug", slug);
    }
    MvcResult result = mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
    UUID id =
        UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.documentId"));
    createdDocuments.add(id);
    return id;
  }

  private UUID createUser() {
    String name = "slug-" + UUID.randomUUID();
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
