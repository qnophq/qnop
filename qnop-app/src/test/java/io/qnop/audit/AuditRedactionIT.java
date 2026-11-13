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
import io.qnop.service.audit.AuditChainVerifier.Problem;
import io.qnop.service.audit.AuditChainVerifier.ProblemKind;
import io.qnop.service.audit.AuditChainVerifier.Report;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * User erasure in the audit trail against a real PostgreSQL (issue #952, ADR-0043): deleting a user
 * pseudonymises every row naming them through the one sanctioned redaction path, keeps the hash
 * chain linked and verifying, records the redaction and who asked for it — while every other way of
 * changing a row, or of writing the reserved events, stays refused. Requires Docker.
 */
class AuditRedactionIT extends AbstractIntegrationTest {

  private static final String RESTRICTED_ROLE = "qnop_restricted_it";
  private static final String ERASED_EVENT = "user.erased";

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
    wipeChain();
  }

  private void wipeChain() {
    jdbc.execute("TRUNCATE TABLE audit_event");
    jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = repeat('0', 64)");
  }

  private User newUser(String displayName) {
    return tx.execute(
        status -> {
          String handle = "u-" + UUID.randomUUID();
          User user = users.save(User.internal(displayName, handle + "@Example.com", handle, "h"));
          createdUsers.add(user.getId());
          return user;
        });
  }

  private AuditEvent saveSystem(String type, UUID actorId, String detail) {
    return tx.execute(status -> auditEvents.save(AuditEvent.system(type, actorId, detail)));
  }

  private Document newDocument(UUID ownerId) {
    return tx.execute(status -> documents.save(new Document(ownerId, "Redacted")));
  }

  /** Deletes the user the way the service does: announce the acting admin, then delete. */
  private void deleteUser(UUID id, UUID actingAdmin) {
    tx.executeWithoutResult(
        status -> {
          jdbc.queryForObject(
              "SELECT set_config('qnop.erasing_actor', ?, true)",
              String.class,
              actingAdmin == null ? "" : actingAdmin.toString());
          jdbc.update("DELETE FROM qnop_user WHERE id = ?", id);
        });
  }

  private Map<String, Object> row(UUID id) {
    return jdbc.queryForMap(
        "SELECT chain_seq, prev_hash, row_hash, actor_id, actor_pseudonym, detail::text AS detail,"
            + " redacted_at, redaction_id FROM audit_event WHERE id = ?",
        id);
  }

  private List<Map<String, Object>> events(String type) {
    return jdbc.queryForList(
        "SELECT actor_id, detail::text AS detail FROM audit_event WHERE event_type = ?"
            + " ORDER BY chain_seq",
        type);
  }

  private String allDetail() {
    return jdbc.queryForObject(
        "SELECT coalesce(string_agg(detail::text, ' '), '') FROM audit_event", String.class);
  }

  private static String memberDetail(UUID subject) {
    return "{\"teamId\":\"t-1\",\"teamName\":\"Alpha\",\"userId\":\""
        + subject
        + "\",\"userName\":\"Old Snapshot Name\",\"role\":\"MEMBER\"}";
  }

  /** Redacted, with the chain columns exactly as they were. */
  private static void assertRedactedInPlace(Map<String, Object> before, Map<String, Object> now) {
    assertThat(now.get("chain_seq")).isEqualTo(before.get("chain_seq"));
    assertThat(now.get("prev_hash")).isEqualTo(before.get("prev_hash"));
    assertThat(now.get("row_hash")).isEqualTo(before.get("row_hash"));
    assertThat(now.get("redacted_at")).isNotNull();
    assertThat(now.get("redaction_id")).isNotNull();
  }

  private static void assertRejected(Runnable statement, String message) {
    assertThatThrownBy(statement::run).rootCause().hasMessageContaining(message);
  }

  @Test
  @DisplayName(
      "deleting a user pseudonymises their rows in place and records redaction and erasure")
  void redactionKeepsHashesAndWritesTheEvents() {
    User admin = newUser("Ada Admin");
    User erased = newUser("Erin Erased");
    User owner = newUser("Owen Owner");
    Document doc = newDocument(owner.getId());
    AuditEvent before = saveSystem("test.before", admin.getId(), "{\"n\":1}");
    AuditEvent added = saveSystem("team.member.added", admin.getId(), memberDetail(erased.getId()));
    AuditEvent acted =
        saveSystem("team.invitation.declined", erased.getId(), memberDetail(erased.getId()));
    // A string that merely equals the user's name, outside the subject object, is not touched.
    AuditEvent onDocument =
        tx.execute(
            status ->
                auditEvents.save(
                    new AuditEvent(
                        doc.getId(),
                        "annotation.created",
                        erased.getId(),
                        "{\"note\":\"Erin Erased\"}")));
    AuditEvent after = saveSystem("test.after", null, "{\"n\":2}");
    Map<String, Object> addedBefore = row(added.getId());
    Map<String, Object> actedBefore = row(acted.getId());
    Map<String, Object> documentBefore = row(onDocument.getId());

    deleteUser(erased.getId(), admin.getId());

    Map<String, Object> addedNow = row(added.getId());
    Map<String, Object> actedNow = row(acted.getId());
    Map<String, Object> documentNow = row(onDocument.getId());
    assertRedactedInPlace(addedBefore, addedNow);
    assertRedactedInPlace(actedBefore, actedNow);
    assertRedactedInPlace(documentBefore, documentNow);
    assertThat(addedNow.get("actor_id")).isEqualTo(admin.getId());
    assertThat(actedNow.get("actor_id")).isNull();
    assertThat(documentNow.get("actor_id")).isNull();
    String pseudonym = (String) actedNow.get("actor_pseudonym");
    assertThat(pseudonym).startsWith("erased-user:").doesNotContain(erased.getId().toString());
    assertThat((String) addedNow.get("detail"))
        .contains("\"userId\": \"" + pseudonym + "\"")
        .contains("\"userName\": \"" + pseudonym + "\"")
        .contains("\"role\": \"MEMBER\"")
        .contains("\"teamName\": \"Alpha\"");
    assertThat((String) documentNow.get("detail")).isEqualTo("{\"note\": \"Erin Erased\"}");
    assertThat(allDetail())
        .doesNotContain(erased.getId().toString())
        .doesNotContain("Old Snapshot Name");
    assertThat(row(before.getId()).get("redacted_at")).isNull();
    assertThat(row(after.getId()).get("redacted_at")).isNull();

    List<Map<String, Object>> redactions = events(AuditChainRepository.REDACTED_EVENT_TYPE);
    assertThat(redactions).hasSize(1);
    String redaction = (String) redactions.getFirst().get("detail");
    String redactionId = addedNow.get("redaction_id").toString();
    assertThat(redaction)
        .contains(redactionId)
        .contains("contentHash")
        .contains((String) actedNow.get("row_hash"))
        .doesNotContain(erased.getId().toString())
        .doesNotContain(pseudonym);
    List<Map<String, Object>> erasures = events(ERASED_EVENT);
    assertThat(erasures).hasSize(1);
    assertThat(erasures.getFirst().get("actor_id")).isEqualTo(admin.getId());
    assertThat((String) erasures.getFirst().get("detail"))
        .isEqualTo("{\"redactionId\": \"" + redactionId + "\"}");

    Report report = verification.verify();
    assertThat(report.problems()).isEmpty();
    assertThat(report.intact()).isTrue();
  }

  @Test
  @DisplayName("a user no audit row names still leaves an attributed user.erased event")
  void unreferencedUserLeavesOnlyTheErasure() {
    User admin = newUser("Ada Admin");
    User bystander = newUser("Bea Bystander");
    saveSystem("test.only", null, "{\"n\":1}");

    deleteUser(bystander.getId(), admin.getId());

    assertThat(events(AuditChainRepository.REDACTED_EVENT_TYPE)).isEmpty();
    List<Map<String, Object>> erasures = events(ERASED_EVENT);
    assertThat(erasures)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.get("actor_id")).isEqualTo(admin.getId());
              assertThat(e.get("detail")).isEqualTo("{\"redactionId\": null}");
            });
    assertThat(verification.verify().intact()).isTrue();
  }

  @Test
  @DisplayName("an unannounced or self-referencing erasing actor is recorded as no actor")
  void unknownErasingActorIsNull() {
    User bystander = newUser("Bea Bystander");

    deleteUser(bystander.getId(), bystander.getId());

    assertThat(events(ERASED_EVENT))
        .singleElement()
        .satisfies(e -> assertThat(e.get("actor_id")).isNull());
  }

  @Test
  @DisplayName("rows written and redacted in one transaction are sealed over the redacted content")
  void unsealedRowsRedactedBeforeTheSeal() {
    User erased = newUser("Una Unsealed");
    saveSystem("test.before", null, "{\"n\":1}");

    UUID written =
        tx.execute(
            status -> {
              AuditEvent event =
                  auditEvents.saveAndFlush(
                      AuditEvent.system("team.invitation.declined", erased.getId(), "{}"));
              jdbc.update("DELETE FROM qnop_user WHERE id = ?", erased.getId());
              return event.getId();
            });

    Map<String, Object> now = row(written);
    assertThat(now.get("chain_seq")).isNotNull();
    assertThat(now.get("actor_id")).isNull();
    assertThat((String) events(AuditChainRepository.REDACTED_EVENT_TYPE).getFirst().get("detail"))
        .contains("\"pendingRows\": 1")
        .contains(written.toString());
    assertThat(verification.verify().problems()).isEmpty();
  }

  @Test
  @DisplayName("a row naming two erased users is redacted twice and still verifies")
  void rowNamingTwoErasedUsers() {
    User first = newUser("Fay First");
    User second = newUser("Sid Second");
    AuditEvent both = saveSystem("team.member.added", first.getId(), memberDetail(second.getId()));

    deleteUser(first.getId(), null);
    UUID firstRedaction = (UUID) row(both.getId()).get("redaction_id");
    deleteUser(second.getId(), null);

    Map<String, Object> now = row(both.getId());
    assertThat(now.get("redaction_id")).isNotEqualTo(firstRedaction);
    assertThat(now.get("actor_id")).isNull();
    assertThat((String) now.get("actor_pseudonym")).startsWith("erased-user:");
    assertThat((String) now.get("detail"))
        .doesNotContain(first.getId().toString())
        .doesNotContain(second.getId().toString())
        .doesNotContain("Old Snapshot Name");
    assertThat(events(AuditChainRepository.REDACTED_EVENT_TYPE)).hasSize(2);
    assertThat(verification.verify().problems()).isEmpty();
  }

  @Test
  @DisplayName("updates outside the erasure trigger are rejected, even with a self-made context")
  void redactionOutsideTheSanctionedPathIsRejected() {
    User actor = newUser("Rex Rejected");
    AuditEvent event = saveSystem("test.update", actor.getId(), "{\"v\":1}");

    assertRejected(
        () ->
            jdbc.update(
                "UPDATE audit_event SET actor_id = NULL, redacted_at = now(),"
                    + " redaction_id = gen_random_uuid() WHERE id = ?",
                event.getId()),
        "append-only");
    // A session that writes its own context (only the schema owner can) and a GUC of its choosing
    // still issues the update outside a trigger, with values that need not be the redacted form.
    UUID redaction = UUID.randomUUID();
    assertRejected(
        () ->
            tx.executeWithoutResult(
                status -> {
                  jdbc.update(
                      "INSERT INTO audit_redaction_context (txid, user_id, redaction_id,"
                          + " pseudonym, redacted_at, phase) VALUES (txid_current(), ?, ?,"
                          + " 'erased-user:mine', now(), 'redact')",
                      actor.getId(),
                      redaction);
                  jdbc.queryForObject(
                      "SELECT set_config('qnop.audit_redaction', ?, true)",
                      String.class,
                      redaction.toString());
                  jdbc.update(
                      "UPDATE audit_event SET actor_id = NULL, actor_pseudonym = 'erased-user:mine',"
                          + " redacted_at = (SELECT redacted_at FROM audit_redaction_context),"
                          + " redaction_id = ? WHERE id = ?",
                      redaction,
                      event.getId());
                }),
        "append-only");
    assertThat(row(event.getId()).get("redacted_at")).isNull();
    assertThat(row(event.getId()).get("actor_id")).isEqualTo(actor.getId());
  }

  @Test
  @DisplayName("the reserved erasure events cannot be inserted, even with a self-made context")
  void forgedReservedEventsAreRejected() {
    String forged = "{\"redactionId\":\"" + UUID.randomUUID() + "\",\"rows\":[],\"pendingRows\":0}";
    assertRejected(
        () -> saveSystem(AuditChainRepository.REDACTED_EVENT_TYPE, null, forged),
        "written only by the database");
    assertRejected(
        () -> saveSystem(ERASED_EVENT, null, "{\"redactionId\":null}"),
        "written only by the database");
    assertRejected(
        () ->
            tx.executeWithoutResult(
                status -> {
                  jdbc.update(
                      "INSERT INTO audit_redaction_context (txid, user_id, redaction_id,"
                          + " pseudonym, redacted_at, phase, event_type, event_detail) VALUES"
                          + " (txid_current(), gen_random_uuid(), gen_random_uuid(), 'x', now(),"
                          + " 'record', 'user.erased', '{\"redactionId\":null}'::jsonb)");
                  jdbc.update(
                      "INSERT INTO audit_event (id, scope, event_type, detail, created_at) VALUES"
                          + " (gen_random_uuid(), 'SYSTEM', 'user.erased',"
                          + " '{\"redactionId\":null}'::jsonb, now())");
                }),
        "written only by the database");
    assertRejected(
        () ->
            jdbc.update(
                "INSERT INTO audit_event (id, scope, event_type, created_at, redacted_at,"
                    + " redaction_id) VALUES (gen_random_uuid(), 'SYSTEM', 'test.x', now(),"
                    + " now(), gen_random_uuid())"),
        "redaction columns");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class)).isZero();
  }

  @Test
  @DisplayName("a redacted row edited afterwards fails verification")
  void contentChangedAfterRedactionIsDetected() {
    User erased = newUser("Eve Edited");
    saveSystem("test.a", null, "{\"v\":1}");
    AuditEvent victim = saveSystem("team.member.added", null, memberDetail(erased.getId()));
    deleteUser(erased.getId(), null);
    long seq = (Long) row(victim.getId()).get("chain_seq");
    assertThat(verification.verify().intact()).isTrue();

    jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER trg_audit_event_no_update");
    try {
      jdbc.update(
          "UPDATE audit_event SET detail = jsonb_set(detail, '{role}', '\"LEAD\"') WHERE id = ?",
          victim.getId());
    } finally {
      jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER trg_audit_event_no_update");
    }

    assertThat(verification.verify().problems())
        .containsExactly(new Problem(ProblemKind.BAD_REDACTION, seq, seq));
  }

  @Test
  @DisplayName("a row marked redacted without a redaction event is reported")
  void redactionWithoutAnEventIsDetected() {
    saveSystem("test.a", null, "{\"v\":1}");
    AuditEvent victim = saveSystem("test.b", null, "{\"v\":1}");
    saveSystem("test.c", null, "{\"v\":1}");

    jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER trg_audit_event_no_update");
    try {
      jdbc.update(
          "UPDATE audit_event SET detail = '{\"v\":2}'::jsonb, redacted_at = now(),"
              + " redaction_id = gen_random_uuid() WHERE id = ?",
          victim.getId());
    } finally {
      jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER trg_audit_event_no_update");
    }

    assertThat(verification.verify().problems())
        .containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("the restricted application role erases a user, but cannot touch the context")
  void restrictedRoleErasesThroughTheTrigger() {
    User admin = newUser("Ada Admin");
    User erased = newUser("Rita Restricted");
    saveSystem("team.member.added", admin.getId(), memberDetail(erased.getId()));
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

      tx.executeWithoutResult(
          status -> {
            jdbc.execute("SET LOCAL ROLE " + RESTRICTED_ROLE);
            jdbc.queryForObject(
                "SELECT set_config('qnop.erasing_actor', ?, true)",
                String.class,
                admin.getId().toString());
            jdbc.update("DELETE FROM qnop_user WHERE id = ?", erased.getId());
          });
      assertRejected(
          () ->
              tx.executeWithoutResult(
                  status -> {
                    jdbc.execute("SET LOCAL ROLE " + RESTRICTED_ROLE);
                    jdbc.update(
                        "INSERT INTO audit_redaction_context (txid, user_id, redaction_id,"
                            + " pseudonym, redacted_at, phase) VALUES (txid_current(),"
                            + " gen_random_uuid(), gen_random_uuid(), 'x', now(), 'redact')");
                  }),
          "permission denied");
      assertRejected(
          () ->
              tx.executeWithoutResult(
                  status -> {
                    jdbc.execute("SET LOCAL ROLE " + RESTRICTED_ROLE);
                    jdbc.execute(
                        "SELECT audit_event_redact_user(gen_random_uuid(), gen_random_uuid())");
                  }),
          "permission denied");
    } finally {
      jdbc.execute("DROP OWNED BY " + RESTRICTED_ROLE);
      jdbc.execute("DROP ROLE " + RESTRICTED_ROLE);
    }

    assertThat(allDetail()).doesNotContain(erased.getId().toString());
    assertThat(events(ERASED_EVENT))
        .singleElement()
        .satisfies(e -> assertThat(e.get("actor_id")).isEqualTo(admin.getId()));
    assertThat(verification.verify().problems()).isEmpty();
  }

  @Test
  @DisplayName(
      "the changeset rollback runs on a trail with redactions and keeps the types reserved")
  void rollbackKeepsTheReservedTypesGuarded() throws IOException {
    User erased = newUser("Rob Rollback");
    saveSystem("team.member.added", null, memberDetail(erased.getId()));
    deleteUser(erased.getId(), null);
    String rollback =
        new ClassPathResource("db/changelog/migrations/0042-audit-redaction-rollback.sql")
            .getContentAsString(StandardCharsets.UTF_8);

    // DDL is transactional in PostgreSQL: apply the rollback, check it, and undo it again.
    assertRejected(
        () ->
            tx.executeWithoutResult(
                status -> {
                  jdbc.execute(rollback);
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT count(*) FROM information_schema.columns"
                                  + " WHERE table_name = 'audit_event'"
                                  + " AND column_name IN ('redaction_id', 'redacted_at',"
                                  + " 'actor_pseudonym')",
                              Long.class))
                      .isZero();
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT to_regclass('audit_redaction_context') IS NULL",
                              Boolean.class))
                      .isTrue();
                  jdbc.update(
                      "INSERT INTO audit_event (id, scope, event_type, detail, created_at) VALUES"
                          + " (gen_random_uuid(), 'SYSTEM', 'audit.chain.redacted', '{}'::jsonb,"
                          + " now())");
                }),
        "written only by the database");

    // Rolled back with the transaction: the redaction path is in place again.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'audit_event'"
                    + " AND column_name = 'redaction_id'",
                Long.class))
        .isEqualTo(1L);
    assertThat(verification.verify().problems()).isEmpty();
  }
}
