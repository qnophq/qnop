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

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.VersionDiffRepository;
import io.qnop.service.diff.VersionDiffService;
import io.qnop.service.diff.VersionDiffService.DiffView;
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

/**
 * The version-diff cache race (issue #351): many threads request the same uncached pair at once.
 * The first insert wins the {@code ux_version_diff_pair} constraint; every loser must catch the
 * violation and return the identical payload — never propagate an error. Because the insert runs in
 * its own {@code REQUIRES_NEW} transaction (VersionDiffCacheWriter), a loser's rollback stays
 * isolated; a regression that inlined the insert into the caller's transaction would poison it and
 * one of the {@code get()} calls below would throw.
 */
class VersionDiffConcurrencyIT extends SeededIntegrationTest {

  private static final int WORKERS = 8;

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private VersionDiffRepository diffCache;
  @Autowired private VersionDiffService diffService;

  @Test
  void concurrentFirstRequestsCacheOnceAndAllReturnTheSamePayload() throws Exception {
    UUID documentId = seedDocument();
    seedVersion(documentId, 1, rendered("the quick brown fox"));
    seedVersion(documentId, 2, rendered("the quick red fox jumps"));

    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
    CyclicBarrier startLine = new CyclicBarrier(WORKERS);
    List<Future<DiffView>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < WORKERS; i++) {
        futures.add(
            pool.submit(
                () -> {
                  startLine.await(5, TimeUnit.SECONDS); // release all workers together
                  return diffService.diff(documentId, 1, 2, MEMBER_ID, false);
                }));
      }

      List<DiffView> results = new ArrayList<>();
      for (Future<DiffView> future : futures) {
        // get() re-throws a worker's exception: a mishandled race surfaces here as a test failure.
        results.add(future.get(15, TimeUnit.SECONDS));
      }

      DiffView first = results.get(0);
      assertThat(results).allSatisfy(view -> assertThat(view).isEqualTo(first));
      assertThat(first.changes()).isNotEmpty(); // the two versions really differ
    } finally {
      pool.shutdownNow();
    }

    // Exactly one row survived the pair-unique race.
    assertThat(diffCache.count()).isEqualTo(1);
  }

  // --- seed helpers (mirroring VersionDiffIT) --------------------------------

  private UUID seedDocument() {
    Document document = documents.save(new Document(MEMBER_ID, "Contract"));
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    return document.getId();
  }

  private void seedVersion(UUID documentId, int number, String renderedJson) {
    DocumentVersion version =
        new DocumentVersion(
            documentId,
            number,
            "sha256/aa/v" + number,
            "hash-v" + number,
            "application/pdf",
            100L,
            MEMBER_ID);
    version.attachRenderedDocument(renderedJson);
    versions.save(version);
  }

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
}
