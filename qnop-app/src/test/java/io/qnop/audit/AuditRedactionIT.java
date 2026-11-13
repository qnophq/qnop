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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * User erasure in the audit trail against a real PostgreSQL (issue #952, ADR-0043): deleting a user
 * pseudonymises every row naming them through the one sanctioned redaction path, keeps the hash
 * chain linked and verifying, and records the redaction — while every other way of changing a row
 * stays refused. Requires Docker.
 */
class AuditRedactionIT extends AbstractIntegrationTest {

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

  private void wipeChain() {
    jdbc.execute("TRUNCATE TABLE audit_event");
    jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = repeat('0', 64)");
  }

  private User newUser(String displayName, String username) {
    return tx.execute(
        status -> {
          User user =
              users.save(
                  User.internal(
                      displayName,
                      username + "-" + UUID.randomUUID() + "@Example.com",
                      username,
                      "h"));
          createdUsers.add(user.getId());
          return user;
        });
  }

  private AuditEvent saveSystem(String type, UUID actorId, String detail) {
    return tx.execute(status -> auditEvents.save(AuditEvent.system(type, actorId, detail)));
  }

  private AuditEvent saveForDocument(UUID documentId, UUID actorId) {
    return tx.execute(
        status ->
            auditEvents.save(
                new AuditEvent(documentId, "annotation.created", actorId, "{\"k\":\"v\"}")));
  }

  private Document newDocument(UUID ownerId) {
    return tx.execute(status -> documents.save(new Document(ownerId, "Redacted")));
  }

  private void deleteUser(UUID id) {
    jdbc.update("DELETE FROM qnop_user WHERE id = ?", id);
  }

  private Map<String, Object> row(UUID id) {
    return jdbc.queryForMap(
        "SELECT chain_seq, prev_hash, row_hash, actor_id, actor_pseudonym, detail::text AS detail,"
            + " redacted_at, redaction_id FROM audit_event WHERE id = ?",
        id);
  }

  private List<String> redactionEvents() {
    return jdbc.queryForList(
        "SELECT detail::text FROM audit_event WHERE event_type = ? ORDER BY chain_seq",
        String.class,
        AuditChainRepository.REDACTED_EVENT_TYPE);
  }

  private static String memberDetail(User subject) {
    return "{\"teamId\":\"t-1\",\"teamName\":\"Alpha\",\"userId\":\""
        + subject.getId()
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

  @Test
  @DisplayName(
      "deleting a user pseudonymises their rows, keeps the hashes and records the redaction")
  void redactionKeepsHashesAndWritesTheEvent() {
    User admin = newUser("Ada Admin", "admin-" + UUID.randomUUID());
    User erased = newUser("Erin Erased", "member");
    User owner = newUser("Owen Owner", "owner-" + UUID.randomUUID());
    Document doc = newDocument(owner.getId());
    AuditEvent before = saveSystem("test.before", admin.getId(), "{\"n\":1}");
    AuditEvent added = saveSystem("team.member.added", admin.getId(), memberDetail(erased));
    AuditEvent acted = saveSystem("team.invitation.declined", erased.getId(), memberDetail(erased));
    AuditEvent onDocument = saveForDocument(doc.getId(), erased.getId());
    AuditEvent after = saveSystem("test.after", null, "{\"n\":2}");
    Map<String, Object> addedBefore = row(added.getId());
    Map<String, Object> actedBefore = row(acted.getId());
    Map<String, Object> documentBefore = row(onDocument.getId());

    deleteUser(erased.getId());

    Map<String, Object> addedNow = row(added.getId());
    Map<String, Object> actedNow = row(acted.getId());
    Map<String, Object> documentNow = row(onDocument.getId());
    assertRedactedInPlace(addedBefore, addedNow);
    assertRedactedInPlace(actedBefore, actedNow);
    assertRedactedInPlace(documentBefore, documentNow);
    // The admin stays the actor of the add; only the subject is pseudonymised.
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
    assertThat(
            jdbc.queryForObject(
                "SELECT string_agg(detail::text, ' ') FROM audit_event", String.class))
        .doesNotContain(erased.getId().toString())
        .doesNotContain("Old Snapshot Name")
        .doesNotContain("Erin Erased");
    assertThat(row(before.getId()).get("redacted_at")).isNull();
    assertThat(row(after.getId()).get("redacted_at")).isNull();

    List<String> events = redactionEvents();
    assertThat(events).hasSize(1);
    assertThat(events.getFirst())
        .contains(addedNow.get("redaction_id").toString())
        .contains((String) addedNow.get("row_hash"))
        .contains((String) actedNow.get("row_hash"))
        .contains((String) documentNow.get("row_hash"))
        .doesNotContain(erased.getId().toString())
        .doesNotContain(pseudonym);

    Report report = verification.verify();
    assertThat(report.problems()).isEmpty();
    assertThat(report.intact()).isTrue();
  }

  @Test
  @DisplayName("a user nobody's audit row names is deleted without a redaction event")
  void unreferencedUserLeavesNoEvent() {
    User bystander = newUser("Bea Bystander", "bea-" + UUID.randomUUID());
    saveSystem("test.only", null, "{\"n\":1}");

    deleteUser(bystander.getId());

    assertThat(redactionEvents()).isEmpty();
    assertThat(verification.verify().intact()).isTrue();
  }

  @Test
  @DisplayName("rows written and redacted in one transaction are sealed over the redacted content")
  void unsealedRowsRedactedBeforeTheSeal() {
    User erased = newUser("Una Unsealed", "una-" + UUID.randomUUID());
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
    assertThat(redactionEvents()).singleElement().asString().contains("\"pendingRows\": 1");
    Report report = verification.verify();
    assertThat(report.problems()).isEmpty();
  }

  @Test
  @DisplayName("a direct UPDATE of the redaction columns is rejected, marker or not")
  void redactionWithoutTheSanctionedPathIsRejected() {
    User actor = newUser("Rex Rejected", "rex-" + UUID.randomUUID());
    AuditEvent event = saveSystem("test.update", actor.getId(), "{\"v\":1}");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE audit_event SET actor_id = NULL, redacted_at = now(),"
                        + " redaction_id = gen_random_uuid() WHERE id = ?",
                    event.getId()))
        .rootCause()
        .hasMessageContaining("append-only");
    // A marker for a different redaction does not sanction this one.
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.queryForObject(
                          "SELECT set_config('qnop.audit_redaction', ?, true)",
                          String.class,
                          UUID.randomUUID().toString());
                      jdbc.update(
                          "UPDATE audit_event SET actor_id = NULL, redacted_at = now(),"
                              + " redaction_id = gen_random_uuid() WHERE id = ?",
                          event.getId());
                    }))
        .rootCause()
        .hasMessageContaining("append-only");
    // Under a matching marker the chain columns still cannot change.
    UUID redaction = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.queryForObject(
                          "SELECT set_config('qnop.audit_redaction', ?, true)",
                          String.class,
                          redaction.toString());
                      jdbc.update(
                          "UPDATE audit_event SET redacted_at = now(), redaction_id = ?,"
                              + " row_hash = repeat('0', 64) WHERE id = ?",
                          redaction,
                          event.getId());
                    }))
        .rootCause()
        .hasMessageContaining("append-only");
    assertThat(row(event.getId()).get("redacted_at")).isNull();
    assertThat(row(event.getId()).get("actor_id")).isEqualTo(actor.getId());
  }

  @Test
  @DisplayName("a forged audit.chain.redacted event or a pre-redacted row cannot be inserted")
  void forgedRedactionInsertsAreRejected() {
    assertThatThrownBy(
            () ->
                saveSystem(
                    AuditChainRepository.REDACTED_EVENT_TYPE,
                    null,
                    "{\"redactionId\":\""
                        + UUID.randomUUID()
                        + "\",\"rows\":[],\"pendingRows\":1}"))
        .rootCause()
        .hasMessageContaining("written only by the database");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO audit_event (id, scope, event_type, created_at, redacted_at,"
                        + " redaction_id) VALUES (gen_random_uuid(), 'SYSTEM', 'test.x', now(),"
                        + " now(), gen_random_uuid())"))
        .rootCause()
        .hasMessageContaining("redaction columns");
  }

  @Test
  @DisplayName("a row changed under a forged redaction id is reported by the verifier")
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

    Report report = verification.verify();
    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }
}
