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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Document;
import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Visit stamping for the unseen markers (issue #307): recording a visit returns the PREVIOUS
 * visit's timestamp and stores the new one atomically — the page compares its whole session against
 * the returned value, so the fresh stamp only matters on the next visit. Personal per user;
 * non-participants read as 404 (anti-enumeration).
 */
class ReviewVisitApiIT extends SeededIntegrationTest {

  @Autowired private DocumentRepository documents;
  @Autowired private ReviewParticipantRepository participants;

  private UUID seedDocument() {
    Document document = documents.save(new Document(MEMBER_ID, "Master services agreement"));
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    return document.getId();
  }

  private MockHttpServletRequestBuilder visit(UUID documentId, UUID user) {
    return post("/api/v1/documents/" + documentId + "/visit")
        .header("Authorization", "Bearer " + token(user));
  }

  @Test
  void firstVisitHasNoPreviousStampAndTheSecondReturnsTheFirst() throws Exception {
    UUID documentId = seedDocument();

    mockMvc
        .perform(visit(documentId, AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.previousSeenAt").doesNotExist());

    mockMvc
        .perform(visit(documentId, AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.previousSeenAt").exists());
  }

  @Test
  void visitsArePersonal() throws Exception {
    UUID documentId = seedDocument();
    mockMvc.perform(visit(documentId, AUDITOR_ID)).andExpect(status().isOk());

    // The owner's first visit is unaffected by the reviewer's stamp.
    mockMvc
        .perform(visit(documentId, MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.previousSeenAt").doesNotExist());
  }

  @Test
  void nonParticipantSees404() throws Exception {
    UUID documentId = seedDocument();

    mockMvc.perform(visit(documentId, EXTERNAL_ID)).andExpect(status().isNotFound());
  }
}
