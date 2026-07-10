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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Document;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.DocumentRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The owner fires the SAME workflow transition on one document twice at once (issue #351) — the
 * endpoint is owner-only, so both racing requests authenticate as the owner. The transition path
 * loads the document with a pessimistic write lock ({@code DocumentRepository.findByIdForUpdate},
 * ADR-0030/#324), so the two requests serialize rather than overwriting each other: the winner
 * commits {@code DRAFT → IN_REVIEW} (200); the loser then re-reads the fresh {@code IN_REVIEW}
 * state, from which {@code IN_REVIEW} is no longer a legal manual edge, and the state machine
 * refuses it with {@code 409 INVALID_TRANSITION}. The observable guarantee — exactly one 200 and
 * one 409, never a double-apply or a 500 — is what protects the review workflow under concurrency.
 *
 * <p>Note: the original ticket assumed the loser would fail on an {@code @Version} optimistic-lock
 * collision; the shipped design instead serializes on the row lock and denies via the state
 * machine, so the loser's 409 carries {@code INVALID_TRANSITION} (not an optimistic-lock code —
 * that path is never reached and has no HTTP mapping). This test asserts the real behaviour.
 *
 * <p>The base class is deliberately not {@code @Transactional}, so each request commits on its own
 * connection — the winner's commit is visible to the loser's {@code SELECT … FOR UPDATE}. Requires
 * Docker (Testcontainers Postgres).
 */
class ReviewWorkflowConcurrencyIT extends SeededIntegrationTest {

  private static final int WORKERS = 2;

  @Autowired private DocumentRepository documents;

  @Test
  void competingTransitionsYieldExactlyOneWinnerAndOneConflict() throws Exception {
    UUID documentId = documents.save(new Document(MEMBER_ID, "Concurrent transition race")).getId();

    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
    CyclicBarrier startLine = new CyclicBarrier(WORKERS);
    try {
      List<Future<MvcResult>> futures = new ArrayList<>();
      for (int i = 0; i < WORKERS; i++) {
        futures.add(
            pool.submit(
                () -> {
                  startLine.await(5, TimeUnit.SECONDS);
                  return mockMvc
                      .perform(
                          post("/api/v1/documents/" + documentId + "/workflow")
                              .header("Authorization", "Bearer " + token(MEMBER_ID))
                              .contentType(MediaType.APPLICATION_JSON)
                              .content("{\"targetState\":\"IN_REVIEW\"}"))
                      .andReturn();
                }));
      }

      int okCount = 0;
      int conflictCount = 0;
      for (Future<MvcResult> future : futures) {
        MvcResult result = future.get(15, TimeUnit.SECONDS);
        int status = result.getResponse().getStatus();
        if (status == 200) {
          okCount++;
          assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.state"))
              .isEqualTo("IN_REVIEW");
        } else if (status == 409) {
          conflictCount++;
          assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code"))
              .isEqualTo("INVALID_TRANSITION");
        } else {
          throw new AssertionError("Unexpected status " + status);
        }
      }

      assertThat(okCount).isEqualTo(1);
      assertThat(conflictCount).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }

    // The race commits the transition exactly once: the document rests in the winner's target.
    assertThat(documents.findById(documentId).orElseThrow().getWorkflowState())
        .isEqualTo(WorkflowState.IN_REVIEW.name());
  }
}
