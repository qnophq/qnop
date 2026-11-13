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
import io.qnop.service.audit.AuditChainVerifier.ProblemKind;
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

  /**
   * Owner-only state left behind: any announced tombstone, and the cascade markers of the given
   * documents. (Markers of documents whose cascade removed no audit row are swept by a later
   * deletion, so other tests' leftovers are deliberately not counted.)
   */
  private long contextRows(UUID... documentIds) {
    long markers = 0;
    for (UUID id : documentIds) {
      markers += count("SELECT count(*) FROM audit_cascade_context WHERE document_id = ?", id);
    }
    return markers + count("SELECT (SELECT count(*) FROM audit_prune_context)");
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
      // Nor can it bind the owner's trigger functions to a table of its own (TEMP is granted to
      // PUBLIC by default): EXECUTE on them is revoked, which is checked at CREATE TRIGGER.
      for (String attach :
          List.of(
              "BEFORE DELETE ON audit_prune_it_mine FOR EACH ROW"
                  + " EXECUTE FUNCTION document_mark_audit_cascade()",
              "AFTER DELETE ON audit_prune_it_mine REFERENCING OLD TABLE AS removed"
                  + " FOR EACH STATEMENT EXECUTE FUNCTION audit_event_record_prune()",
              "AFTER INSERT ON audit_prune_it_mine FOR EACH ROW"
                  + " EXECUTE FUNCTION audit_event_seal()",
              "BEFORE DELETE ON audit_prune_it_mine FOR EACH ROW"
                  + " EXECUTE FUNCTION qnop_user_redact_audit()")) {
        assertRejected(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.execute("SET LOCAL ROLE " + RESTRICTED_ROLE);
                      jdbc.execute(
                          "CREATE TEMP TABLE audit_prune_it_mine (id UUID, scope TEXT,"
                              + " document_id UUID, chain_seq BIGINT, prev_hash TEXT,"
                              + " row_hash TEXT) ON COMMIT DROP");
                      jdbc.execute("CREATE TRIGGER audit_prune_it_attach " + attach);
                    }),
            "permission denied for function");
      }
    } finally {
      jdbc.execute("DROP OWNED BY " + RESTRICTED_ROLE);
      jdbc.execute("DROP ROLE " + RESTRICTED_ROLE);
    }

    assertThat(count("SELECT count(*) FROM audit_event WHERE document_id = ?", doc.getId()))
        .isZero();
    assertThat(tombstones()).singleElement().asString().contains(doc.getId().toString());
    assertThat(contextRows(doc.getId())).isZero();
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
          // Spent by the end of the deleting statement, not only at commit.
          assertThat(contextRows(first.getId(), second.getId())).isZero();
          explainDeletion(first.getId());
          explainDeletion(second.getId());
        });

    assertThat(tombstones())
        .singleElement()
        .asString()
        .contains(first.getId().toString())
        .contains(second.getId().toString());
    assertThat(contextRows(first.getId(), second.getId())).isZero();
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
    assertThat(contextRows(doc.getId(), other.getId())).isZero();
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
          assertThat(contextRows(doc.getId())).isZero();
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

  @Test
  @DisplayName("the owner's trigger functions refuse to run as a trigger on any other table")
  void definerTriggerFunctionsAreBoundToTheirTables() {
    Document doc = newDocument();
    jdbc.execute(
        "CREATE TABLE audit_prune_it_own (id UUID, scope TEXT, document_id UUID,"
            + " chain_seq BIGINT, prev_hash TEXT, row_hash TEXT)");
    try {
      List<String> triggers =
          List.of(
              "BEFORE DELETE ON audit_prune_it_own FOR EACH ROW"
                  + " EXECUTE FUNCTION document_mark_audit_cascade()",
              "AFTER DELETE ON audit_prune_it_own REFERENCING OLD TABLE AS removed"
                  + " FOR EACH STATEMENT EXECUTE FUNCTION audit_event_record_prune()",
              "AFTER INSERT ON audit_prune_it_own FOR EACH ROW"
                  + " EXECUTE FUNCTION audit_event_seal()",
              "BEFORE DELETE ON audit_prune_it_own FOR EACH ROW"
                  + " EXECUTE FUNCTION qnop_user_redact_audit()");
      for (String trigger : triggers) {
        // Even the owner, who may attach them, cannot make them act for another table.
        assertRejected(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.execute("CREATE TRIGGER audit_prune_it_bound " + trigger);
                      jdbc.update(
                          "INSERT INTO audit_prune_it_own (id, scope, document_id, chain_seq)"
                              + " VALUES (?, 'DOCUMENT', ?, 1)",
                          doc.getId(),
                          doc.getId());
                      jdbc.update("DELETE FROM audit_prune_it_own");
                    }),
            "runs only as the");
      }
    } finally {
      jdbc.execute("DROP TABLE audit_prune_it_own");
    }

    assertThat(contextRows(doc.getId())).isZero();
    assertThat(tombstones()).isEmpty();
  }

  @Test
  @DisplayName("a cascade from a parent table reaching documents on several paths prunes them all")
  void cascadeFromAParentOverSeveralPaths() {
    Document direct = newDocument();
    Document viaFirst = newDocument();
    Document viaSecond = newDocument();
    Document withoutTrail = newDocument();
    saveSystem("test.before");
    saveForDocument(direct.getId(), "test.direct");
    saveForDocument(viaFirst.getId(), "test.via.first");
    saveForDocument(viaSecond.getId(), "test.via.second");
    saveForDocument(direct.getId(), "test.direct.again");
    saveSystem("test.after");

    // The schema has no parent of document today; build one for this transaction only and roll
    // it back: documents reached directly from the parent, and one level deeper through two rows
    // of an intermediate table.
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.execute("CREATE TABLE audit_prune_it_parent (id INT PRIMARY KEY)");
                      jdbc.execute(
                          "CREATE TABLE audit_prune_it_mid (id INT PRIMARY KEY, parent INT"
                              + " REFERENCES audit_prune_it_parent (id) ON DELETE CASCADE)");
                      jdbc.execute(
                          "ALTER TABLE document"
                              + " ADD COLUMN it_parent INT REFERENCES audit_prune_it_parent (id)"
                              + " ON DELETE CASCADE,"
                              + " ADD COLUMN it_mid INT REFERENCES audit_prune_it_mid (id)"
                              + " ON DELETE CASCADE");
                      jdbc.execute("INSERT INTO audit_prune_it_parent VALUES (1)");
                      jdbc.execute("INSERT INTO audit_prune_it_mid VALUES (10, 1), (11, 1)");
                      jdbc.update(
                          "UPDATE document SET it_parent = 1 WHERE id IN (?, ?)",
                          direct.getId(),
                          withoutTrail.getId());
                      jdbc.update("UPDATE document SET it_mid = 10 WHERE id = ?", viaFirst.getId());
                      jdbc.update(
                          "UPDATE document SET it_mid = 11 WHERE id = ?", viaSecond.getId());

                      jdbc.update("DELETE FROM audit_prune_it_parent");

                      List<UUID> trailed =
                          List.of(direct.getId(), viaFirst.getId(), viaSecond.getId());
                      assertThat(
                              count(
                                  "SELECT count(*) FROM document WHERE id IN (?, ?, ?, ?)",
                                  direct.getId(),
                                  viaFirst.getId(),
                                  viaSecond.getId(),
                                  withoutTrail.getId()))
                          .isZero();
                      assertThat(
                              count(
                                  "SELECT count(*) FROM audit_event WHERE document_id IS NOT NULL"))
                          .isZero();
                      assertThat(tombstones())
                          .singleElement()
                          .asString()
                          .contains(trailed.stream().map(UUID::toString).toList());
                      assertThat(contextRows(trailed.toArray(UUID[]::new))).isZero();
                      throw new IllegalStateException("rollback-sentinel");
                    }))
        .hasMessage("rollback-sentinel");

    assertThat(count("SELECT count(*) FROM document WHERE id = ?", direct.getId())).isEqualTo(1L);
    assertVerifiesClean();
  }

  @Test
  @DisplayName("malformed tombstones are reported without stopping the verification")
  void malformedTombstonesAreReportedNotFatal() {
    Document doc = newDocument();
    saveSystem("test.before");
    saveForDocument(doc.getId(), "test.doc");
    saveSystem("test.after");
    tx.executeWithoutResult(
        status -> {
          jdbc.update("DELETE FROM document WHERE id = ?", doc.getId());
          explainDeletion(doc.getId());
        });
    List<String> malformed =
        List.of(
            "[]",
            "{\"documentIds\": \"x\", \"ranges\": [1]}",
            "{\"documentIds\": [\"a\"], \"ranges\": [null]}",
            "{\"documentIds\": [\"a\"], \"ranges\": [{\"fromSeq\": \"1\", \"toSeq\": {}}]}");
    jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER trg_audit_event_guard_insert");
    try {
      for (String detail : malformed) {
        jdbc.update(
            "INSERT INTO audit_event (id, scope, event_type, detail, created_at)"
                + " VALUES (gen_random_uuid(), 'SYSTEM', ?, ?::jsonb, now())",
            AuditChainRepository.PRUNED_EVENT_TYPE,
            detail);
      }
    } finally {
      jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER trg_audit_event_guard_insert");
    }

    Report report = verification.verify();

    // Each malformed one is a finding and vouches for nothing; the real one still bridges its gap.
    assertThat(report.problems())
        .hasSize(malformed.size())
        .allSatisfy(p -> assertThat(p.kind()).isEqualTo(ProblemKind.BAD_PRUNE_RANGE));
    assertThat(report.intact()).isFalse();
  }

  @Test
  @DisplayName("a marker no tombstone spent is swept by a later deletion")
  void unspentMarkersAreSwept() {
    Document withoutTrail = newDocument();
    tx.executeWithoutResult(
        status -> jdbc.update("DELETE FROM document WHERE id = ?", withoutTrail.getId()));
    // No audit row went, so no tombstone names it; the marker outlives its transaction.
    assertThat(contextRows(withoutTrail.getId())).isEqualTo(1L);

    // Swept by the next deletion once no transaction older than it is running. Retried, because
    // a concurrent transaction elsewhere can briefly hold the horizon back.
    for (int attempt = 0; attempt < 5 && contextRows(withoutTrail.getId()) > 0; attempt++) {
      Document next = newDocument();
      tx.executeWithoutResult(
          status -> jdbc.update("DELETE FROM document WHERE id = ?", next.getId()));
    }
    assertThat(contextRows(withoutTrail.getId())).isZero();
  }
}
