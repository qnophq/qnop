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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.service.convert.LibreOfficeConverter;
import io.qnop.service.convert.ThrottledOfficeConverter;
import io.qnop.testsupport.SeededIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * What a PDF export does when every conversion slot is already taken (issue #651).
 *
 * <p>The slot is held by calling the converter directly rather than by firing a second export: what
 * is under test is the answer the busy one gets, and a real parallel download would add threading
 * to the test without adding anything to the assertion.
 *
 * <p>The office binary is mocked, because CI and developer machines disagree about whether one
 * exists — and the limit has to hold either way.
 */
@TestPropertySource(properties = {"qnop.office.max-concurrent=1", "qnop.office.max-wait=0s"})
class AnnotationExportBusyIT extends SeededIntegrationTest {

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private ThrottledOfficeConverter converter;

  @MockitoBean private LibreOfficeConverter office;

  private final ExecutorService threads = Executors.newSingleThreadExecutor();
  private final CountDownLatch converting = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  private UUID documentId;

  @BeforeEach
  void seedAndOccupyTheConverter() throws Exception {
    Document document = new Document(MEMBER_ID, "Vendor agreement");
    document.setWorkflowState(WorkflowState.IN_REVIEW);
    documentId = documents.save(document).getId();
    versions.save(
        new DocumentVersion(
            documentId, 1, "sha256/aa/deadbeef", "deadbeef", "application/pdf", 1234L, MEMBER_ID));
    participants.save(ReviewParticipant.forUser(documentId, MEMBER2_ID));

    when(office.isAvailable()).thenReturn(true);
    when(office.toPdf(any(), any()))
        .thenAnswer(
            invocation -> {
              converting.countDown();
              release.await(30, TimeUnit.SECONDS);
              return "%PDF-1.7".getBytes(StandardCharsets.UTF_8);
            });

    threads.submit(() -> converter.toPdf(new byte[] {1, 2, 3}, "docx"));
    assertThat(converting.await(10, TimeUnit.SECONDS)).isTrue();
  }

  @AfterEach
  void freeTheConverter() {
    release.countDown();
    threads.shutdownNow();
  }

  @Test
  @Timeout(60)
  @DisplayName("a PDF export with no free slot is refused as busy, not as broken")
  void refusesWhenEverySlotIsTaken() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/documents/" + documentId + "/annotations/export")
                .param("format", "pdf")
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        // 503, because nothing is wrong: the instance is already converting as much
        // as it allows itself, and the answer must not read like a defect.
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("EXPORT_BUSY"))
        // The one part a client can act on without parsing prose.
        .andExpect(header().string("Retry-After", "1"));
  }

  @Test
  @Timeout(60)
  @DisplayName("a format that needs no converter is unaffected by a busy one")
  void otherFormatsStillWork() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/documents/" + documentId + "/annotations/export")
                .param("format", "xlsx")
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk());
  }
}
