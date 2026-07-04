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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.job.JobQueuePoller;
import io.qnop.testsupport.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * Multi-version review lifecycle over the REAL document fixtures (issue #370): the shared {@code
 * testdata/documents} families run through the full pipeline — multipart upload, MinIO staging, the
 * durable extraction job, versioned serving and the inter-version diff — instead of synthetic
 * one-line PDFs. {@code doc1} (test-dummy) self-identifies each version in its text; {@code doc2}
 * (scifi story) carries real word-level edits between versions. Not {@code @Transactional} for the
 * same reason as {@link DocumentIngestIT}. Requires Docker.
 */
@AutoConfigureMockMvc
class DocumentFixtureLifecycleIT extends AbstractIntegrationTest {

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
  @DisplayName("doc1: five uploaded versions all extract READY and each serves its own text")
  void multiVersionLifecycleOverTestDummyFixture() throws Exception {
    UUID owner = createUser();
    UUID documentId = uploadNewDocument(owner, "Fixture lifecycle", fixture("doc1", 1));
    for (int version = 2; version <= 5; version++) {
      uploadVersion(owner, documentId, fixture("doc1", version), version);
    }

    // One poll claims up to 20 due jobs; the second drains anything a first-
    // batch job enqueued (re-anchoring after new versions).
    poller.poll();
    poller.poll();

    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.latestVersionNumber").value(5));
    for (int version = 1; version <= 5; version++) {
      mockMvc
          .perform(get("/api/v1/documents/{id}/versions", documentId).with(asUser(owner)))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.versions[?(@.versionNumber == %d)].extractionStatus".formatted(version))
                  .value("READY"));
    }

    // Every version keeps ITS OWN rendering — the fixture text names itself.
    assertThat(renderedText(owner, documentId, 1)).contains("TEST-DUMMY-V1");
    assertThat(renderedText(owner, documentId, 5)).contains("TEST-DUMMY-V5");
  }

  @Test
  @DisplayName("doc2: the story's v1→v2 word edit surfaces in the located diff")
  void diffOverScifiStoryFixture() throws Exception {
    UUID owner = createUser();
    UUID documentId = uploadNewDocument(owner, "Fixture diff", fixture("doc2", 1));
    uploadVersion(owner, documentId, fixture("doc2", 2), 2);
    uploadVersion(owner, documentId, fixture("doc2", 5), 3);

    poller.poll();
    poller.poll();

    // The known v1→v2 edit: "…auf ihrem letzten Kurs…" became "…einsamen…".
    String diff =
        mockMvc
            .perform(get("/api/v1/documents/{id}/diff?from=1&to=2", documentId).with(asUser(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fromVersion").value(1))
            .andExpect(jsonPath("$.toVersion").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> fromTexts = JsonPath.read(diff, "$.changes[*].fromText");
    List<String> toTexts = JsonPath.read(diff, "$.changes[*].toText");
    assertThat(fromTexts).anySatisfy(text -> assertThat(text).contains("letzten"));
    assertThat(toTexts).anySatisfy(text -> assertThat(text).contains("einsamen"));

    // Any pair is diffable (ADR-0034), not only adjacent versions.
    mockMvc
        .perform(get("/api/v1/documents/{id}/diff?from=1&to=3", documentId).with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(org.hamcrest.Matchers.greaterThan(0)));
  }

  // --- helpers ---------------------------------------------------------------

  private static MockMultipartFile fixture(String family, int version) {
    String name =
        family.equals("doc1")
            ? "documents/doc1/test-dummy-v%d.pdf".formatted(version)
            : "documents/doc2/scifi-story-v%d.pdf".formatted(version);
    return new MockMultipartFile("file", "fixture.pdf", "application/pdf", TestData.bytes(name));
  }

  private UUID uploadNewDocument(UUID owner, String title, MockMultipartFile file)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                multipart("/api/v1/documents").file(file).param("title", title).with(asUser(owner)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.versionNumber").value(1))
            .andReturn();
    UUID id =
        UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.documentId"));
    createdDocuments.add(id);
    return id;
  }

  private void uploadVersion(
      UUID owner, UUID documentId, MockMultipartFile file, int expectedVersion) throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/documents/{id}/versions", documentId).file(file).with(asUser(owner)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.versionNumber").value(expectedVersion));
  }

  private String renderedText(UUID owner, UUID documentId, int version) throws Exception {
    String rendered =
        mockMvc
            .perform(
                get("/api/v1/documents/{id}/versions/{v}/rendered", documentId, version)
                    .with(asUser(owner)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> texts = JsonPath.read(rendered, "$.surfaces[*].textSpans[*].text");
    return String.join(" ", texts);
  }

  private UUID createUser() {
    String name = "fix-" + UUID.randomUUID();
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
}
