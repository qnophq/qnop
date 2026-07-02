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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.VersionDiffRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Inter-version diff acceptance (issue #249, ADR-0034): located changes between two versions, the
 * permanent cache, the extraction gate, and participant-only visibility. Owner {@code MEMBER_ID},
 * reviewer {@code AUDITOR_ID}; {@code EXTERNAL_ID} is a non-participant (404, anti-enumeration).
 */
class VersionDiffIT extends SeededIntegrationTest {

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private VersionDiffRepository diffCache;

  /** The jsonb of a one-surface rendered document whose spans are the given lines (ADR-0032). */
  private static String rendered(String... lines) {
    StringBuilder spans = new StringBuilder();
    int offset = 0;
    double y = 0.1;
    for (String line : lines) {
      if (!spans.isEmpty()) {
        spans.append(',');
      }
      spans.append(
          "{\"text\":\"%s\",\"startOffset\":%d,\"endOffset\":%d,\"box\":{\"x\":0.1,\"y\":%s,\"width\":0.8,\"height\":0.05}}"
              .formatted(line, offset, offset + line.length(), y));
      offset += line.length() + 1;
      y += 0.1;
    }
    return "{\"surfaces\":[{\"index\":0,\"width\":612.0,\"height\":792.0,\"textSpans\":["
        + spans
        + "]}]}";
  }

  private UUID seedDocument() {
    Document document = documents.save(new Document(MEMBER_ID, "Contract"));
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    return document.getId();
  }

  private DocumentVersion seedVersion(UUID documentId, int number, String renderedJson) {
    DocumentVersion version =
        new DocumentVersion(
            documentId,
            number,
            "sha256/aa/v" + number,
            "hash-v" + number,
            "application/pdf",
            100L,
            MEMBER_ID);
    if (renderedJson != null) {
      version.attachRenderedDocument(renderedJson);
    }
    return versions.save(version);
  }

  private MockHttpServletRequestBuilder diffRequest(UUID documentId, int from, int to, UUID user) {
    return get("/api/v1/documents/" + documentId + "/diff")
        .param("from", String.valueOf(from))
        .param("to", String.valueOf(to))
        .header("Authorization", "Bearer " + token(user));
  }

  @Test
  void returnsLocatedChangesBetweenTwoVersions() throws Exception {
    UUID documentId = seedDocument();
    seedVersion(documentId, 1, rendered("the quick brown fox", "second line stays"));
    seedVersion(documentId, 2, rendered("the quick red fox", "second line stays"));

    mockMvc
        .perform(diffRequest(documentId, 1, 2, AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fromVersion").value(1))
        .andExpect(jsonPath("$.toVersion").value(2))
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].type").value("CHANGED"))
        .andExpect(jsonPath("$.changes[0].fromText").value("brown"))
        .andExpect(jsonPath("$.changes[0].toText").value("red"))
        .andExpect(jsonPath("$.changes[0].fromLocations[0].surfaceIndex").value(0))
        .andExpect(jsonPath("$.changes[0].fromLocations[0].box.y").value(0.1))
        .andExpect(jsonPath("$.changes[0].toLocations[0].box.width").value(0.8));
  }

  @Test
  void secondRequestIsServedFromThePermanentCache() throws Exception {
    UUID documentId = seedDocument();
    DocumentVersion v1 = seedVersion(documentId, 1, rendered("alpha"));
    DocumentVersion v2 = seedVersion(documentId, 2, rendered("alpha beta"));

    mockMvc.perform(diffRequest(documentId, 1, 2, MEMBER_ID)).andExpect(status().isOk());
    assertThat(diffCache.findByFromVersionIdAndToVersionId(v1.getId(), v2.getId())).isPresent();

    // The cached second call returns the identical payload.
    mockMvc
        .perform(diffRequest(documentId, 1, 2, MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].type").value("INSERTED"))
        .andExpect(jsonPath("$.changes[0].toText").value("beta"));
    assertThat(diffCache.count()).isEqualTo(1);
  }

  @Test
  void identicalVersionsYieldAnEmptyChangeList() throws Exception {
    UUID documentId = seedDocument();
    seedVersion(documentId, 1, rendered("same text"));
    seedVersion(documentId, 2, rendered("same text"));

    mockMvc
        .perform(diffRequest(documentId, 1, 2, MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(0));
  }

  @Test
  void nonParticipantSees404() throws Exception {
    UUID documentId = seedDocument();
    seedVersion(documentId, 1, rendered("alpha"));
    seedVersion(documentId, 2, rendered("beta"));

    mockMvc.perform(diffRequest(documentId, 1, 2, EXTERNAL_ID)).andExpect(status().isNotFound());
  }

  @Test
  void pendingExtractionIsConflict() throws Exception {
    UUID documentId = seedDocument();
    seedVersion(documentId, 1, rendered("alpha"));
    seedVersion(documentId, 2, null); // extraction still PENDING

    mockMvc
        .perform(diffRequest(documentId, 1, 2, MEMBER_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXTRACTION_PENDING"));
  }

  @Test
  void equalFromAndToIsRejected() throws Exception {
    UUID documentId = seedDocument();
    seedVersion(documentId, 1, rendered("alpha"));

    mockMvc
        .perform(diffRequest(documentId, 1, 1, MEMBER_ID))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void unknownVersionIsNotFound() throws Exception {
    UUID documentId = seedDocument();
    seedVersion(documentId, 1, rendered("alpha"));

    mockMvc.perform(diffRequest(documentId, 1, 9, MEMBER_ID)).andExpect(status().isNotFound());
  }
}
