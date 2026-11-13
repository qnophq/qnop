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
import io.qnop.service.audit.AuditChainVerificationService;
import io.qnop.service.audit.AuditChainVerifier.Report;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The document cascade and its prune tombstones are authorised by owner-only state, never by a
 * session setting (issue #959, ADR-0043): the cascade marker and the tombstone context live in
 * tables only the schema owner can write, and are cleared within the transaction. Requires Docker.
 */
class AuditPruneGuardIT extends AbstractIntegrationTest {

  private static final String RESTRICTED_ROLE = "qnop_prune_restricted_it";
  private static final String PROBE_TABLE = "audit_prune_it_probe";

  @Autowired AuditEventRepository auditEvents;
  @Autowired AuditChainVerificationService verification;
  @Autowired DocumentRepository documents;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private final List<UUID> createdUsers = new ArrayList<>();

  @BeforeEach
  void setUp() {
    tx = new TransactionTemplate(transactionManager);
    wipeChain();
    // Runs the statement it is given from inside a trigger (pg_trigger_depth() = 2), the way a
    // trigger or function of someone else's making would.
    jdbc.execute("CREATE TABLE " + PROBE_TABLE + " (stmt TEXT)");
    jdbc.execute(
        """
        CREATE FUNCTION audit_prune_it_probe_fn() RETURNS TRIGGER LANGUAGE plpgsql AS $$
        BEGIN
          EXECUTE NEW.stmt;
          RETURN NEW;
        END;
        $$
        """);
    jdbc.execute(
        "CREATE TRIGGER audit_prune_it_probe_trg AFTER INSERT ON "
            + PROBE_TABLE
            + " FOR EACH ROW EXECUTE FUNCTION audit_prune_it_probe_fn()");
  }

  @AfterEach
  void cleanUp() {
    jdbc.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
    jdbc.execute("DROP FUNCTION IF EXISTS audit_prune_it_probe_fn()");
    wipeChain();
    createdUsers.forEach(id -> jdbc.update("DELETE FROM document WHERE owner_id = ?", id));
    wipeChain();
    createdUsers.forEach(id -> jdbc.update("DELETE FROM qnop_user WHERE id = ?", id));
    wipeChain();
  }

  private void wipeChain() {
    jdbc.execute("TRUNCATE TABLE audit_event");
    jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = repeat('0', 64)");
  }

  private Document newDocument() {
    return tx.execute(
        status -> {
          User owner =
              users.save(User.external("Owner", "prune-" + UUID.randomUUID() + "@example.com"));
          createdUsers.add(owner.getId());
          return documents.save(new Document(owner.getId(), "Pruned"));
        });
  }

  private AuditEvent saveForDocument(UUID documentId, String type) {
    return tx.execute(
        status -> auditEvents.save(new AuditEvent(documentId, type, null, "{\"k\":\"v\"}")));
  }

  private AuditEvent saveSystem(String type) {
    return tx.execute(status -> auditEvents.save(AuditEvent.system(type, null, "{\"n\":1}")));
  }

  /** The record the review-deletion service leaves, so the verifier can explain the prune. */
  private void explainDeletion(UUID documentId) {
    jdbc.update(
        "INSERT INTO audit_event (id, scope, event_type, detail, created_at) VALUES"
            + " (gen_random_uuid(), 'SYSTEM', 'review.deleted', jsonb_build_object('documentId',"
            + " ?::text), now())",
        documentId.toString());
  }

  private void runFromTrigger(String statement) {
    jdbc.update("INSERT INTO " + PROBE_TABLE + " VALUES (?)", statement);
  }

  private List<String> tombstones() {
    return jdbc.queryForList(
        "SELECT detail::text FROM audit_event WHERE event_type = ? ORDER BY chain_seq",
        String.class,
        AuditChainRepository.PRUNED_EVENT_TYPE);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private long contextRows() {
    return count("SELECT (SELECT count(*) FROM audit_cascade_context)")
        + count("SELECT (SELECT count(*) FROM audit_prune_context)");
  }

  private static void assertRejected(Runnable statement, String message) {
    assertThatThrownBy(statement::run).rootCause().hasMessageContaining(message);
  }

  private void assertVerifiesClean() {
    Report report = verification.verify();
    assertThat(report.problems()).isEmpty();
    assertThat(report.intact()).isTrue();
  }

  @Test
  @DisplayName("a session-set cascade marker sanctions no delete, of document or system rows")
  void sessionCascadeSettingSanctionsNothing() {
    Document doc = newDocument();
    AuditEvent onDocument = saveForDocument(doc.getId(), "test.doc");
    AuditEvent system = saveSystem("test.system");
    UUID absent = UUID.randomUUID();

    tx.executeWithoutResult(
        status -> {
          jdbc.queryForObject(
              "SELECT set_config('qnop.audit_cascade_docs', ?, true)",
              String.class,
              doc.getId() + "," + absent);
          assertThat(
                  jdbc.queryForObject(
                      "SELECT audit_event_cascade_sanctioned('DOCUMENT', ?)",
                      Boolean.class,
                      absent))
              .isFalse();
        });
    for (UUID victim : List.of(onDocument.getId(), system.getId())) {
      assertRejected(
          () ->
              tx.executeWithoutResult(
                  status -> {
                    jdbc.queryForObject(
                        "SELECT set_config('qnop.audit_cascade_docs', ?, true)",
                        String.class,
                        doc.getId() + "," + absent);
                    runFromTrigger("DELETE FROM audit_event WHERE id = '" + victim + "'");
                  }),
          "append-only");
    }

    assertThat(count("SELECT count(*) FROM audit_event")).isEqualTo(2L);
    assertVerifiesClean();
  }

  @Test
  @DisplayName("a tombstone cannot be written with a session setting, even from inside a trigger")
  void sessionTombstoneSettingIsRejected() {
    assertRejected(
        () ->
            tx.executeWithoutResult(
                status -> {
                  jdbc.queryForObject(
                      "SELECT set_config('qnop.audit_writing_tombstone', 'on', true)",
                      String.class);
                  runFromTrigger(
                      "INSERT INTO audit_event (id, scope, event_type, detail, created_at)"
                          + " VALUES (gen_random_uuid(), 'SYSTEM', 'audit.chain.pruned',"
                          + " '{}'::jsonb, now())");
                }),
        "written only by the database");
    assertThat(tombstones()).isEmpty();
  }

  @Test
  @DisplayName("a tombstone cannot be written against a self-made context outside the trigger")
  void forgedTombstoneContextIsRejected() {
    assertRejected(
        () ->
            tx.executeWithoutResult(
                status -> {
                  jdbc.update(
                      "INSERT INTO audit_prune_context (txid, event_detail)"
                          + " VALUES (txid_current(), '{}'::jsonb)");
                  jdbc.update(
                      "INSERT INTO audit_event (id, scope, event_type, detail, created_at)"
                          + " VALUES (gen_random_uuid(), 'SYSTEM', 'audit.chain.pruned',"
                          + " '{}'::jsonb, now())");
                }),
        "written only by the database");
    assertThat(tombstones()).isEmpty();
    assertThat(contextRows()).isZero();
  }

  @Test
  @DisplayName("the restricted role deletes a document, its trail is pruned with a tombstone")
  void restrictedRoleDeletesThroughTheCascade() {
    Document doc = newDocument();
    saveSystem("test.before");
    saveForDocument(doc.getId(), "test.doc.1");
    saveForDocument(doc.getId(), "test.doc.2");
    saveSystem("test.after");
    String schema = jdbc.queryForObject("SELECT current_schema()", String.class);
    jdbc.execute("DROP ROLE IF EXISTS " + RESTRICTED_ROLE);
    jdbc.execute("CREATE ROLE " + RESTRICTED_ROLE + " NOLOGIN");
    try {
      // The grants docs/DEPLOYMENT.md prescribes for the restricted application role.
      jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + RESTRICTED_ROLE);
      jdbc.execute(
          "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
              + schema
              + " TO "
              + RESTRICTED_ROLE);
      jdbc.execute(
          "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA " + schema + " TO " + RESTRICTED_ROLE);
      jdbc.execute("REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM " + RESTRICTED_ROLE);
      jdbc.execute(
          "REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON audit_chain_head FROM " + RESTRICTED_ROLE);
      jdbc.execute("REVOKE ALL ON audit_redaction_context FROM " + RESTRICTED_ROLE);
      jdbc.execute("REVOKE ALL ON audit_cascade_context FROM " + RESTRICTED_ROLE);
      jdbc.execute("REVOKE ALL ON audit_prune_context FROM " + RESTRICTED_ROLE);

      tx.executeWithoutResult(
          status -> {
            jdbc.execute("SET LOCAL ROLE " + RESTRICTED_ROLE);
            jdbc.update("DELETE FROM document WHERE id = ?", doc.getId());
            explainDeletion(doc.getId());
          });
      for (String forge :
          List.of(
              "INSERT INTO audit_cascade_context (txid, document_id)"
                  + " VALUES (txid_current(), gen_random_uuid())",
              "INSERT INTO audit_prune_context (txid, event_detail)"
                  + " VALUES (txid_current(), '{}'::jsonb)")) {
        assertRejected(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.execute("SET LOCAL ROLE " + RESTRICTED_ROLE);
                      jdbc.update(forge);
                    }),
            "permission denied");
      }
    } finally {
      jdbc.execute("DROP OWNED BY " + RESTRICTED_ROLE);
      jdbc.execute("DROP ROLE " + RESTRICTED_ROLE);
    }

    assertThat(count("SELECT count(*) FROM audit_event WHERE document_id = ?", doc.getId()))
        .isZero();
    assertThat(tombstones()).singleElement().asString().contains(doc.getId().toString());
    assertThat(contextRows()).isZero();
    assertVerifiesClean();
  }

  @Test
  @DisplayName("one statement deleting several documents leaves one tombstone naming all of them")
  void multiDocumentDelete() {
    Document first = newDocument();
    Document second = newDocument();
    saveSystem("test.before");
    saveForDocument(first.getId(), "test.first");
    saveForDocument(second.getId(), "test.second");
    saveForDocument(first.getId(), "test.first.again");
    saveSystem("test.after");

    tx.executeWithoutResult(
        status -> {
          jdbc.update("DELETE FROM document WHERE id IN (?, ?)", first.getId(), second.getId());
          // Cleared at the end of the deleting statement, not only at commit.
          assertThat(
                  count("SELECT count(*) FROM audit_cascade_context WHERE txid = txid_current()"))
              .isZero();
          explainDeletion(first.getId());
          explainDeletion(second.getId());
        });

    assertThat(tombstones())
        .singleElement()
        .asString()
        .contains(first.getId().toString())
        .contains(second.getId().toString());
    assertThat(contextRows()).isZero();
    assertVerifiesClean();
  }

  @Test
  @DisplayName("deleting, recreating and deleting a document in one transaction prunes it twice")
  void sameIdDeletedTwiceInOneTransaction() {
    Document doc = newDocument();
    Document other = newDocument();
    saveForDocument(doc.getId(), "test.doc");
    saveForDocument(other.getId(), "test.other");

    tx.executeWithoutResult(
        status -> {
          jdbc.execute(
              "CREATE TEMP TABLE audit_prune_it_copy ON COMMIT DROP AS"
                  + " SELECT * FROM document WHERE id = '"
                  + doc.getId()
                  + "'");
          jdbc.update("DELETE FROM document WHERE id = ?", doc.getId());
          jdbc.execute("INSERT INTO document SELECT * FROM audit_prune_it_copy");
          auditEvents.saveAndFlush(new AuditEvent(doc.getId(), "test.recreated", null, null));
          // The recreated document's rows are protected again: its earlier deletion left no
          // marker behind.
          assertThat(
                  jdbc.queryForObject(
                      "SELECT audit_event_cascade_sanctioned('DOCUMENT', ?)",
                      Boolean.class,
                      doc.getId()))
              .isFalse();
          jdbc.update("DELETE FROM document WHERE id = ?", doc.getId());
          jdbc.update("DELETE FROM document WHERE id = ?", other.getId());
          explainDeletion(doc.getId());
          explainDeletion(other.getId());
        });

    assertThat(tombstones()).hasSize(2);
    assertThat(count("SELECT count(*) FROM audit_event WHERE document_id IS NOT NULL")).isZero();
    assertThat(contextRows()).isZero();
    assertVerifiesClean();
  }

  @Test
  @DisplayName("a delete rolled back to a savepoint leaves no marker and no tombstone")
  void savepointRollbackLeavesNothingBehind() {
    Document doc = newDocument();
    AuditEvent onDocument = saveForDocument(doc.getId(), "test.doc");

    tx.executeWithoutResult(
        status -> {
          jdbc.execute("SAVEPOINT before_delete");
          jdbc.update("DELETE FROM document WHERE id = ?", doc.getId());
          jdbc.execute("ROLLBACK TO SAVEPOINT before_delete");
          assertThat(contextRows()).isZero();
        });
    assertRejected(
        () -> runFromTrigger("DELETE FROM audit_event WHERE id = '" + onDocument.getId() + "'"),
        "append-only");

    assertThat(tombstones()).isEmpty();
    assertThat(count("SELECT count(*) FROM audit_event WHERE id = ?", onDocument.getId()))
        .isEqualTo(1L);
    assertVerifiesClean();
  }

  @Test
  @DisplayName("the changeset rollback restores the previous guards and keeps tombstones guarded")
  void rollbackRestoresThePreviousGuards() throws IOException {
    Document doc = newDocument();
    saveForDocument(doc.getId(), "test.doc");
    String rollback =
        new ClassPathResource("db/changelog/migrations/0043-audit-prune-context-rollback.sql")
            .getContentAsString(StandardCharsets.UTF_8);

    // DDL is transactional in PostgreSQL: apply the rollback, exercise it, and undo it again.
    assertRejected(
        () ->
            tx.executeWithoutResult(
                status -> {
                  jdbc.execute(rollback);
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT to_regclass('audit_cascade_context') IS NULL"
                                  + " AND to_regclass('audit_prune_context') IS NULL",
                              Boolean.class))
                      .isTrue();
                  // The restored cascade and tombstone writer still prune a document.
                  jdbc.update("DELETE FROM document WHERE id = ?", doc.getId());
                  assertThat(tombstones()).hasSize(1);
                  jdbc.update(
                      "INSERT INTO audit_event (id, scope, event_type, detail, created_at) VALUES"
                          + " (gen_random_uuid(), 'SYSTEM', 'audit.chain.pruned', '{}'::jsonb,"
                          + " now())");
                }),
        "written only by the database");

    // Rolled back with the transaction: the owner-only state is in place again.
    assertThat(
            jdbc.queryForObject(
                "SELECT to_regclass('audit_cascade_context') IS NOT NULL", Boolean.class))
        .isTrue();
    assertThat(count("SELECT count(*) FROM document WHERE id = ?", doc.getId())).isEqualTo(1L);
    assertThat(tombstones()).isEmpty();
    assertVerifiesClean();
  }
}
