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
package io.qnop.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.spi.storage.StorageProvider;
import io.qnop.testsupport.SeededIntegrationTest;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The admin storage-consistency dashboard over the wire (issue #523, ADR-0044) against a real
 * MinIO: the ADMIN-only authorization gate, the scan surfacing orphaned + missing binaries, and
 * orphan deletion with its in-transaction re-check. Uses content-unique keys and CONTAINS
 * assertions so it stays isolated from whatever else shares the JVM's bucket.
 */
class AdminStorageConsistencyIT extends SeededIntegrationTest {

  private static final String SCAN = "/api/v1/admin/storage-consistency";
  private static final String DELETE = "/api/v1/admin/storage-consistency/orphans/delete";

  @Autowired private StorageProvider provider;
  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  private static String keyFor(byte[] content) throws Exception {
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    return "sha256/" + hash.substring(0, 2) + "/" + hash;
  }

  /** Writes a bucket object with no database reference — a true orphan. Returns its key. */
  private String putOrphan() throws Exception {
    byte[] content = ("orphan-" + UUID.randomUUID()).getBytes(UTF_8);
    String key = keyFor(content);
    provider.put(key, new ByteArrayInputStream(content), content.length, "application/pdf");
    return key;
  }

  /** Seeds a document version whose storage key is NOT in the bucket — a missing binary. */
  private String seedMissingVersion(String title) throws Exception {
    Document document = documents.save(new Document(MEMBER_ID, title));
    String missingKey = keyFor(("missing-" + UUID.randomUUID()).getBytes(UTF_8));
    versions.save(
        new DocumentVersion(
            document.getId(), 1, missingKey, "deadbeef", "application/pdf", 1234L, MEMBER_ID));
    return missingKey;
  }

  private static String deleteBody(String... keys) {
    String joined = String.join("\",\"", keys);
    return "{\"keys\":[\"" + joined + "\"]}";
  }

  @Test
  @DisplayName("requires authentication")
  void requiresAuth() throws Exception {
    mockMvc.perform(get(SCAN)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("a non-admin (MEMBER, AUDITOR) is forbidden")
  void nonAdminForbidden() throws Exception {
    mockMvc.perform(as(get(SCAN), MEMBER_ID)).andExpect(status().isForbidden());
    mockMvc.perform(as(get(SCAN), AUDITOR_ID)).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("an admin scan surfaces orphaned objects and missing binaries")
  void adminScanFindsOrphanAndMissing() throws Exception {
    String orphanKey = putOrphan();
    String missingKey = seedMissingVersion("Lost contract");
    try {
      mockMvc
          .perform(as(get(SCAN), ADMIN_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.orphaned[*].storageKey", hasItem(orphanKey)))
          .andExpect(jsonPath("$.missing[*].storageKey", hasItem(missingKey)))
          .andExpect(jsonPath("$.missing[*].documentTitle", hasItem("Lost contract")));
    } finally {
      provider.delete(orphanKey);
    }
  }

  @Test
  @DisplayName("an admin deletes an orphan, and it is gone afterwards")
  void adminDeletesOrphan() throws Exception {
    String orphanKey = putOrphan();

    mockMvc
        .perform(
            as(post(DELETE), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deleteBody(orphanKey)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted", hasItem(orphanKey)))
        .andExpect(jsonPath("$.skipped").isEmpty());

    assertThat(provider.exists(orphanKey)).isFalse();
  }

  @Test
  @DisplayName("deletion skips a key that is referenced again since the scan (in-tx re-check)")
  void deleteSkipsReferencedKey() throws Exception {
    // A key that now has a version row must not be deleted, even if asked.
    String referencedKey = seedMissingVersion("Still referenced");

    mockMvc
        .perform(
            as(post(DELETE), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deleteBody(referencedKey)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").isEmpty())
        .andExpect(jsonPath("$.skipped[0].storageKey").value(referencedKey))
        .andExpect(jsonPath("$.skipped[0].reason").value("now referenced"));
  }
}
