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
import io.qnop.service.review.ReviewDeletionService;
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
import org.springframework.transaction.TransactionDefinition;
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
  @Autowired ReviewDeletionService reviewDeletion;

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

    reviewDeletion.delete(doc.getId(), doc.getOwnerId(), true);

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

  @Test
  @DisplayName("a document deleted without a review-deletion record is an unexplained prune")
  void unexplainedCascadeDetected() {
    Document doc = newDocument();
    saveForDocument(doc.getId(), "test.doc");
    saveSystem("test.after", null);

    jdbc.update("DELETE FROM document WHERE id = ?", doc.getId());

    assertThat(verification.verify().problems())
        .extracting(Problem::kind)
        .containsExactly(ProblemKind.UNEXPLAINED_PRUNE);
  }

  @Test
  @DisplayName("a delete issued from another trigger is rejected while the document exists")
  void deleteFromNonCascadeTriggerRejected() {
    Document doc = newDocument();
    saveForDocument(doc.getId(), "test.doc");
    UUID eventId =
        jdbc.queryForObject(
            "SELECT id FROM audit_event WHERE document_id = ?", UUID.class, doc.getId());

    jdbc.execute("CREATE TABLE audit_chain_it_probe (id UUID)");
    jdbc.execute(
        """
        CREATE FUNCTION audit_chain_it_probe_fn() RETURNS TRIGGER LANGUAGE plpgsql AS $$
        BEGIN
          DELETE FROM audit_event WHERE id = NEW.id;
          RETURN NEW;
        END;
        $$
        """);
    jdbc.execute(
        "CREATE TRIGGER audit_chain_it_probe_trg AFTER INSERT ON audit_chain_it_probe"
            + " FOR EACH ROW EXECUTE FUNCTION audit_chain_it_probe_fn()");
    try {
      assertThatThrownBy(
              () ->
                  tx.executeWithoutResult(
                      status -> {
                        // Even with the cascade marker set for this document.
                        jdbc.queryForObject(
                            "SELECT set_config('qnop.audit_cascade_docs', ?, true)",
                            String.class,
                            doc.getId().toString());
                        jdbc.update("INSERT INTO audit_chain_it_probe VALUES (?)", eventId);
                      }))
          .rootCause()
          .hasMessageContaining("append-only");
    } finally {
      jdbc.execute("DROP TABLE audit_chain_it_probe");
      jdbc.execute("DROP FUNCTION audit_chain_it_probe_fn()");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE id = ?", Long.class, eventId))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("an insert carrying chain values is rejected")
  void preSealedInsertRejected() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO audit_event (id, scope, event_type, created_at, chain_seq,"
                        + " prev_hash, row_hash) VALUES (gen_random_uuid(), 'SYSTEM', 'test.forged',"
                        + " now(), 1, repeat('0', 64), repeat('a', 64))"))
        .rootCause()
        .hasMessageContaining("assigned by the database");
  }

  @Test
  @DisplayName("a tombstone cannot be inserted directly, even with the writer flag set")
  void directTombstoneInsertRejected() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.queryForObject(
                          "SELECT set_config('qnop.audit_writing_tombstone', 'on', true)",
                          String.class);
                      jdbc.update(
                          "INSERT INTO audit_event (id, scope, event_type, detail, created_at)"
                              + " VALUES (gen_random_uuid(), 'SYSTEM', ?, '{}'::jsonb, now())",
                          AuditChainRepository.PRUNED_EVENT_TYPE);
                    }))
        .rootCause()
        .hasMessageContaining("written only by the database");
  }

  @Test
  @DisplayName("a malformed tombstone is reported, not fatal")
  void malformedTombstoneReported() {
    saveSystem("test.a", null);
    jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER trg_audit_event_guard_insert");
    try {
      jdbc.update(
          "INSERT INTO audit_event (id, scope, event_type, detail, created_at)"
              + " VALUES (gen_random_uuid(), 'SYSTEM', ?, '{\"ranges\": \"oops\"}'::jsonb, now())",
          AuditChainRepository.PRUNED_EVENT_TYPE);
    } finally {
      jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER trg_audit_event_guard_insert");
    }

    Report report = verification.verify();

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.BAD_PRUNE_RANGE, 2, 2));
    assertThat(verification.lastKnownIntact()).contains(false);
  }

  @Test
  @DisplayName("a session-local table cannot stand in for the chain head")
  void tempTableCannotShadowHead() {
    tx.executeWithoutResult(
        status -> {
          jdbc.execute(
              "CREATE TEMP TABLE audit_chain_head (id SMALLINT, last_seq BIGINT,"
                  + " last_hash VARCHAR(64)) ON COMMIT DROP");
          jdbc.execute("INSERT INTO audit_chain_head VALUES (1, 100, repeat('f', 64))");
          auditEvents.saveAndFlush(AuditEvent.system("test.shadow", null, null));
        });

    assertThat(jdbc.queryForList("SELECT chain_seq FROM audit_event", Long.class))
        .containsExactly(1L);
    assertThat(verification.verify().intact()).isTrue();
  }

  @Test
  @DisplayName("audit writes under snapshot isolation fail with a clear message")
  void nonReadCommittedWriterRejected() {
    TransactionTemplate repeatable = new TransactionTemplate(transactionManager);
    repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

    assertThatThrownBy(
            () ->
                repeatable.executeWithoutResult(
                    status -> auditEvents.save(AuditEvent.system("test.rr", null, null))))
        .rootCause()
        .hasMessageContaining("READ COMMITTED");
  }
}
