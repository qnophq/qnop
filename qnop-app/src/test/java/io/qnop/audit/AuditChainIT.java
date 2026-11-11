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
package io.qnop.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.entity.User;
import io.qnop.repository.AuditChainRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.audit.AuditChainBrokenException;
import io.qnop.service.audit.AuditChainHasher;
import io.qnop.service.audit.AuditChainVerificationService;
import io.qnop.service.audit.AuditChainVerifier.Problem;
import io.qnop.service.audit.AuditChainVerifier.ProblemKind;
import io.qnop.service.audit.AuditChainVerifier.Report;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit hash chain against a real PostgreSQL (issue #928, ADR-0043): commit-time sealing under
 * concurrency, the append-only guards, prune tombstones for the sanctioned document cascade, and
 * the verifier telling those apart from changes made behind the application's back. Requires
 * Docker.
 */
class AuditChainIT extends AbstractIntegrationTest {

  @Autowired AuditEventRepository auditEvents;
  @Autowired AuditChainVerificationService verification;
  @Autowired DocumentRepository documents;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private final List<UUID> createdUsers = new ArrayList<>();

  @BeforeEach
  void resetChain() {
    tx = new TransactionTemplate(transactionManager);
    wipeChain();
  }

  @AfterEach
  void cleanUp() {
    wipeChain();
    createdUsers.forEach(id -> jdbc.update("DELETE FROM document WHERE owner_id = ?", id));
    wipeChain();
    createdUsers.forEach(id -> jdbc.update("DELETE FROM qnop_user WHERE id = ?", id));
  }

  /** TRUNCATE bypasses the row triggers; the head is reset with it (as clean.sql does). */
  private void wipeChain() {
    jdbc.execute("TRUNCATE TABLE audit_event");
    jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = repeat('0', 64)");
  }

  private AuditEvent saveSystem(String type, String detail) {
    return tx.execute(status -> auditEvents.save(AuditEvent.system(type, null, detail)));
  }

  private Document newDocument() {
    return tx.execute(
        status -> {
          User owner =
              users.save(User.external("Owner", "chain-" + UUID.randomUUID() + "@example.com"));
          createdUsers.add(owner.getId());
          return documents.save(new Document(owner.getId(), "Chained"));
        });
  }

  private void saveForDocument(UUID documentId, String type) {
    tx.executeWithoutResult(
        status -> auditEvents.save(new AuditEvent(documentId, type, null, "{\"k\":\"v\"}")));
  }

  private Map<String, Object> chainColumns(UUID id) {
    return jdbc.queryForMap(
        "SELECT chain_seq, prev_hash, row_hash FROM audit_event WHERE id = ?", id);
  }

  @Test
  @DisplayName("each committed event is sealed in order and matches the Java re-computation")
  void sealsOnCommit() {
    AuditEvent first = saveSystem("test.one", "{\"b\":2,\"a\":1}");
    AuditEvent second = saveSystem("test.two", null);

    Map<String, Object> one = chainColumns(first.getId());
    Map<String, Object> two = chainColumns(second.getId());
    assertThat(one.get("chain_seq")).isEqualTo(1L);
    assertThat(one.get("prev_hash")).isEqualTo(AuditChainHasher.GENESIS);
    assertThat(two.get("chain_seq")).isEqualTo(2L);
    assertThat(two.get("prev_hash")).isEqualTo(one.get("row_hash"));

    Report report = verification.verify();
    assertThat(report.intact()).isTrue();
    assertThat(report.rowsChecked()).isEqualTo(2);
    assertThat(report.headHash()).isEqualTo(two.get("row_hash"));
  }

  @Test
  @DisplayName("concurrent writers produce one gap-free chain")
  void concurrentInsertsFormOneChain() throws Exception {
    int threads = 8;
    int perThread = 15;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Callable<Void>> writers = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        int writer = t;
        writers.add(
            () -> {
              for (int i = 0; i < perThread; i++) {
                saveSystem("test.concurrent", "{\"writer\":" + writer + ",\"i\":" + i + "}");
              }
              return null;
            });
      }
      for (Future<Void> done : pool.invokeAll(writers)) {
        done.get();
      }
    } finally {
      pool.shutdownNow();
    }

    List<Long> seqs =
        jdbc.queryForList("SELECT chain_seq FROM audit_event ORDER BY chain_seq", Long.class);
    assertThat(seqs)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, threads * perThread).boxed().toList());
    assertThat(verification.verify().intact()).isTrue();
  }

  @Test
  @DisplayName("an UPDATE of an audit row is rejected by the database")
  void updateRejected() {
    AuditEvent event = saveSystem("test.update", "{\"v\":1}");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE audit_event SET detail = '{\"v\":2}'::jsonb WHERE id = ?",
                    event.getId()))
        .rootCause()
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () -> jdbc.update("UPDATE audit_event SET chain_seq = 99 WHERE id = ?", event.getId()))
        .rootCause()
        .hasMessageContaining("append-only");
  }

  @Test
  @DisplayName("a direct DELETE of an audit row is rejected by the database")
  void directDeleteRejected() {
    AuditEvent event = saveSystem("test.delete", null);

    assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE id = ?", event.getId()))
        .rootCause()
        .hasMessageContaining("append-only");
  }

  @Test
  @DisplayName("deleting a document prunes its trail with a tombstone the verifier accepts")
  void sanctionedCascadeIsNotFlagged() {
    Document doc = newDocument();
    saveSystem("test.before", null);
    saveForDocument(doc.getId(), "test.doc.1");
    saveSystem("test.between", null);
    saveForDocument(doc.getId(), "test.doc.2");
    saveForDocument(doc.getId(), "test.doc.3");
    saveSystem("test.after", null);

    jdbc.update("DELETE FROM document WHERE id = ?", doc.getId());

    List<String> tombstones =
        jdbc.queryForList(
            "SELECT detail::text FROM audit_event WHERE event_type = ?",
            String.class,
            AuditChainRepository.PRUNED_EVENT_TYPE);
    assertThat(tombstones).hasSize(1);
    assertThat(tombstones.getFirst()).contains(doc.getId().toString());
    Report report = verification.verify();
    assertThat(report.problems()).isEmpty();
    assertThat(report.intact()).isTrue();
  }

  @Test
  @DisplayName("an event written and cascaded away in one transaction leaves the chain intact")
  void insertAndCascadeInOneTransaction() {
    Document doc = newDocument();
    tx.executeWithoutResult(
        status -> {
          auditEvents.saveAndFlush(new AuditEvent(doc.getId(), "test.short-lived", null, null));
          jdbc.update("DELETE FROM document WHERE id = ?", doc.getId());
        });
    saveSystem("test.after", null);

    assertThat(verification.verify().intact()).isTrue();
  }

  @Test
  @DisplayName("an edit made with the guard disabled is detected and recorded")
  void tamperedContentDetected() {
    saveSystem("test.a", "{\"v\":1}");
    AuditEvent victim = saveSystem("test.b", "{\"v\":1}");
    saveSystem("test.c", "{\"v\":1}");

    jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER trg_audit_event_no_update");
    try {
      jdbc.update(
          "UPDATE audit_event SET detail = '{\"v\":2}'::jsonb WHERE id = ?", victim.getId());
    } finally {
      jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER trg_audit_event_no_update");
    }

    Report report = verification.verify();
    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.CONTENT_MISMATCH, 2, 2));

    assertThatThrownBy(() -> verification.verifyOnce(false))
        .isInstanceOf(AuditChainBrokenException.class);
    assertThat(
            jdbc.queryForList(
                "SELECT detail::text FROM audit_event WHERE event_type = ?",
                String.class,
                AuditChainVerificationService.AUDIT_CHAIN_BROKEN))
        .singleElement()
        .asString()
        .contains("CONTENT_MISMATCH");
  }

  @Test
  @DisplayName("a row removed with the guard disabled is an unsanctioned gap")
  void unsanctionedDeleteDetected() {
    saveSystem("test.a", null);
    AuditEvent victim = saveSystem("test.b", null);
    saveSystem("test.c", null);

    jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER USER");
    try {
      jdbc.update("DELETE FROM audit_event WHERE id = ?", victim.getId());
    } finally {
      jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER USER");
    }

    assertThat(verification.verify().problems())
        .containsExactly(new Problem(ProblemKind.UNSANCTIONED_GAP, 2, 2));
  }
}
