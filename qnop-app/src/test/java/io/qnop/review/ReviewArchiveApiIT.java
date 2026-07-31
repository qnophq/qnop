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
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.review.ReviewArchiveService;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.testsupport.SeededIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The review-archive retention flag end-to-end (issue #576): the terminal transition stamping
 * {@code closed_at}, the scheduled sweep behind the scheduler gate (retention window, dry-run,
 * {@code 0} disables), the overview's archived/active split, and the manual archive/unarchive
 * endpoints with their 409/403/404 envelope.
 *
 * <p>Complements the DB-free {@code ReviewArchiveServiceTest}: what is exercised here is precisely
 * what a unit test cannot reach — the {@code findVisibleTo} JPQL retention branch, the nullable
 * {@code archived_at}/{@code closed_at} columns of changeset 0026, and the HTTP surface.
 */
class ReviewArchiveApiIT extends SeededIntegrationTest {

  private static final String SCHEDULER_JOB =
      "/api/v1/admin/scheduler/" + SchedulerJobCatalog.REVIEW_ARCHIVE;
  private static final String SCHEDULER_RUN = SCHEDULER_JOB + "/run";
  private static final String RETENTION_KEY =
      ApplicationSettingKey.REVIEW_ARCHIVE_AFTER_DAYS.getKey();

  @Autowired private DocumentRepository documents;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private ApplicationSettingsService settings;
  @Autowired private ReviewParticipantRepository participants;

  /**
   * {@code application_setting} deliberately survives {@code clean.sql} (it is migration-seeded),
   * so a retention change would leak into every later test in the JVM-shared database. Restore it.
   */
  @AfterEach
  void restoreRetentionDefault() {
    settings.update(
        Map.of(RETENTION_KEY, ApplicationSettingKey.REVIEW_ARCHIVE_AFTER_DAYS.getDefaultValue()),
        null);
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  /** A review owned by MEMBER, closed {@code daysAgo} days ago — the sweep's precondition. */
  private Document closedReview(String title, WorkflowState terminal, long daysAgo) {
    Document document = new Document(MEMBER_ID, title);
    document.setWorkflowState(terminal);
    document.setClosedAt(Instant.now().minus(Duration.ofDays(daysAgo)));
    return documents.save(document);
  }

  private Document reload(UUID documentId) {
    return documents.findById(documentId).orElseThrow();
  }

  private void setRetentionDays(int days) {
    settings.update(Map.of(RETENTION_KEY, String.valueOf(days)), null);
  }

  // ── closed_at ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("a terminal transition stamps closed_at; a non-terminal one leaves it null")
  void terminalTransitionStampsClosedAt() throws Exception {
    Document document = documents.save(new Document(MEMBER_ID, "Archive closed-at agreement"));
    String workflow = "/api/v1/documents/" + document.getId() + "/workflow";

    mockMvc
        .perform(
            as(post(workflow), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"IN_REVIEW\"}"))
        .andExpect(status().isOk());
    assertThat(reload(document.getId()).getClosedAt()).isNull();

    mockMvc
        .perform(
            as(post(workflow), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"CANCELLED\"}"))
        .andExpect(status().isOk());
    assertThat(reload(document.getId()).getClosedAt()).isNotNull();
  }

  // ── the scheduled sweep ─────────────────────────────────────────────────

  @Test
  @DisplayName("the sweep archives a long-closed review, leaves a recent one, and audits as System")
  void sweepArchivesOnlyReviewsPastTheRetentionWindow() throws Exception {
    setRetentionDays(90);
    Document stale = closedReview("Archive sweep stale", WorkflowState.FINALIZED, 200);
    Document recent = closedReview("Archive sweep recent", WorkflowState.CANCELLED, 5);

    mockMvc
        .perform(as(post(SCHEDULER_RUN), ADMIN_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastOutcome").value("SUCCESS"));

    assertThat(reload(stale.getId()).getArchivedAt()).isNotNull();
    assertThat(reload(recent.getId()).getArchivedAt()).isNull();

    // Machine-driven: a null actor renders as "System" in the audit trail (ADR-0042).
    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(stale.getId()))
        .anySatisfy(
            event -> {
              assertThat(event.getEventType())
                  .isEqualTo(ReviewArchiveService.AUDIT_REVIEW_ARCHIVED);
              assertThat(event.getActorId()).isNull();
              assertThat(event.getDetail()).contains("FINALIZED");
            });
    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(recent.getId()))
        .extracting(AuditEvent::getEventType)
        .doesNotContain(ReviewArchiveService.AUDIT_REVIEW_ARCHIVED);
  }

  @Test
  @DisplayName("the job is dry-run capable, and a dry run archives nothing")
  void dryRunLeavesEveryReviewUntouched() throws Exception {
    setRetentionDays(90);
    Document stale = closedReview("Archive dry-run stale", WorkflowState.FINALIZED, 200);

    // Dry-run is job configuration (issue #524), so it is switched on before the run.
    mockMvc
        .perform(
            as(patch(SCHEDULER_JOB), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supportsDryRun").value(true))
        .andExpect(jsonPath("$.dryRun").value(true));

    mockMvc
        .perform(as(post(SCHEDULER_RUN), ADMIN_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.lastOutcome").value("SUCCESS"));

    assertThat(reload(stale.getId()).getArchivedAt()).isNull();
  }

  @Test
  @DisplayName("a retention of 0 disables auto-archiving; re-enabling is picked up by the next run")
  void retentionZeroDisablesTheSweep() throws Exception {
    setRetentionDays(0);
    Document stale = closedReview("Archive disabled stale", WorkflowState.FINALIZED, 900);

    mockMvc.perform(as(post(SCHEDULER_RUN), ADMIN_ID)).andExpect(status().isOk());
    assertThat(reload(stale.getId()).getArchivedAt()).isNull();

    // The retention is read per pass, so the admin's change needs no restart.
    setRetentionDays(90);
    mockMvc.perform(as(post(SCHEDULER_RUN), ADMIN_ID)).andExpect(status().isOk());
    assertThat(reload(stale.getId()).getArchivedAt()).isNotNull();
  }

  @Test
  @DisplayName("the retention is changeable through the admin settings API and respected at once")
  void retentionIsChangeableThroughTheAdminSettingsApi() throws Exception {
    setRetentionDays(90);
    Document stale = closedReview("Archive settings-api stale", WorkflowState.FINALIZED, 30);

    // At 90 days a review closed 30 days ago is not yet eligible.
    mockMvc.perform(as(post(SCHEDULER_RUN), ADMIN_ID)).andExpect(status().isOk());
    assertThat(reload(stale.getId()).getArchivedAt()).isNull();

    mockMvc
        .perform(
            as(patch("/api/v1/admin/settings"), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"" + RETENTION_KEY + "\":\"7\"}}"))
        .andExpect(status().isOk());

    mockMvc.perform(as(post(SCHEDULER_RUN), ADMIN_ID)).andExpect(status().isOk());
    assertThat(reload(stale.getId()).getArchivedAt()).isNotNull();
  }

  // ── the overview split ──────────────────────────────────────────────────

  @Test
  @DisplayName("the overview hides archived reviews; the archived facet returns only them")
  void overviewSplitsActiveFromArchived() throws Exception {
    Document active = documents.save(new Document(MEMBER_ID, "Archive facet active"));
    Document archived = closedReview("Archive facet archived", WorkflowState.FINALIZED, 200);
    archived.setArchivedAt(Instant.now());
    documents.save(archived);

    mockMvc
        .perform(as(get("/api/v1/documents?q=Archive facet"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(active.getId().toString()))
        .andExpect(jsonPath("$.items[0].archivedAt").doesNotExist());

    mockMvc
        .perform(as(get("/api/v1/documents?q=Archive facet&scope=archived"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(archived.getId().toString()))
        .andExpect(jsonPath("$.items[0].archivedAt").exists());
  }

  @Test
  @DisplayName("an archived review stays individually readable, with archivedAt on the response")
  void archivedReviewStaysReadable() throws Exception {
    Document archived = closedReview("Archive readable record", WorkflowState.FINALIZED, 200);
    archived.setArchivedAt(Instant.now());
    documents.save(archived);

    mockMvc
        .perform(as(get("/api/v1/documents/" + archived.getId()), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowState").value("FINALIZED"))
        .andExpect(jsonPath("$.archivedAt").exists());
  }

  // ── manual archive / unarchive ──────────────────────────────────────────

  @Test
  @DisplayName("the owner archives a closed review and unarchives it again, both audited")
  void ownerArchivesAndUnarchives() throws Exception {
    Document document = closedReview("Archive manual round-trip", WorkflowState.FINALIZED, 1);

    mockMvc
        .perform(as(post("/api/v1/documents/" + document.getId() + "/archive"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archivedAt").exists());
    assertThat(reload(document.getId()).getArchivedAt()).isNotNull();

    mockMvc
        .perform(as(post("/api/v1/documents/" + document.getId() + "/unarchive"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archivedAt").doesNotExist());
    assertThat(reload(document.getId()).getArchivedAt()).isNull();

    // A manual action carries the acting user, unlike the sweep's null actor.
    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(document.getId()))
        .anySatisfy(
            event -> {
              assertThat(event.getEventType())
                  .isEqualTo(ReviewArchiveService.AUDIT_REVIEW_ARCHIVED);
              assertThat(event.getActorId()).isEqualTo(MEMBER_ID);
            })
        .anySatisfy(
            event -> {
              assertThat(event.getEventType())
                  .isEqualTo(ReviewArchiveService.AUDIT_REVIEW_UNARCHIVED);
              assertThat(event.getActorId()).isEqualTo(MEMBER_ID);
            });
  }

  @Test
  @DisplayName("an admin may archive a review they do not own")
  void adminArchivesAForeignReview() throws Exception {
    Document document = closedReview("Archive admin reach", WorkflowState.CANCELLED, 1);

    mockMvc
        .perform(as(post("/api/v1/documents/" + document.getId() + "/archive"), ADMIN_ID))
        .andExpect(status().isOk());

    assertThat(reload(document.getId()).getArchivedAt()).isNotNull();
  }

  @Test
  @DisplayName("archiving an open review is refused with REVIEW_NOT_CLOSED")
  void archivingAnOpenReviewIsRefused() throws Exception {
    Document open = documents.save(new Document(MEMBER_ID, "Archive open refusal"));

    mockMvc
        .perform(as(post("/api/v1/documents/" + open.getId() + "/archive"), MEMBER_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_NOT_CLOSED"));

    assertThat(reload(open.getId()).getArchivedAt()).isNull();
  }

  @Test
  @DisplayName("archiving twice and unarchiving an active review are both 409s")
  void doubleArchiveAndStrayUnarchiveAreRefused() throws Exception {
    Document document = closedReview("Archive idempotence refusal", WorkflowState.FINALIZED, 1);

    mockMvc
        .perform(as(post("/api/v1/documents/" + document.getId() + "/archive"), MEMBER_ID))
        .andExpect(status().isOk());
    mockMvc
        .perform(as(post("/api/v1/documents/" + document.getId() + "/archive"), MEMBER_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_ARCHIVED"));

    Document active = closedReview("Archive stray unarchive", WorkflowState.FINALIZED, 1);
    mockMvc
        .perform(as(post("/api/v1/documents/" + active.getId() + "/unarchive"), MEMBER_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_NOT_ARCHIVED"));
  }

  @Test
  @DisplayName("a non-participant gets 404, not 403 — archive must not leak that the review exists")
  void archivingHidesExistenceFromNonParticipants() throws Exception {
    Document document = closedReview("Archive anti-enumeration", WorkflowState.FINALIZED, 1);

    // The read and delete paths answer a non-participant 404 so an id is not
    // enumerable (issue #661); archive and unarchive must answer the same, not a
    // 403 that confirms the id resolves to a real review. MEMBER2 is not a
    // participant of this review, so it is indistinguishable from an unknown id.
    mockMvc
        .perform(as(post("/api/v1/documents/" + document.getId() + "/archive"), MEMBER2_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    mockMvc
        .perform(as(post("/api/v1/documents/" + document.getId() + "/unarchive"), MEMBER2_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));

    // The same 404 an unknown id gives — the two are meant to be indistinguishable.
    mockMvc
        .perform(as(post("/api/v1/documents/" + UUID.randomUUID() + "/archive"), MEMBER_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));

    assertThat(reload(document.getId()).getArchivedAt()).isNull();
  }

  @Test
  @DisplayName("a participant who is not the owner gets the honest 403 — they already see it")
  void archivingIsOwnerOnlyForParticipants() throws Exception {
    Document document = closedReview("Archive participant refusal", WorkflowState.FINALIZED, 1);
    participants.save(ReviewParticipant.forUser(document.getId(), MEMBER2_ID));

    // A reviewer can already see the review, so hiding it would be pointless; 403
    // tells them plainly that archiving is the owner's call.
    mockMvc
        .perform(as(post("/api/v1/documents/" + document.getId() + "/archive"), MEMBER2_ID))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_DOCUMENT_OWNER"));

    assertThat(reload(document.getId()).getArchivedAt()).isNull();
  }

  // ── the sweep's reach ───────────────────────────────────────────────────

  @Test
  @DisplayName("the sweep ignores a review that is closed but already archived")
  void sweepSkipsAnAlreadyArchivedReview() throws Exception {
    setRetentionDays(90);
    Document document = closedReview("Archive sweep idempotence", WorkflowState.FINALIZED, 200);
    Instant archivedAt = Instant.now().minus(Duration.ofDays(3));
    document.setArchivedAt(archivedAt);
    documents.save(document);

    mockMvc.perform(as(post(SCHEDULER_RUN), ADMIN_ID)).andExpect(status().isOk());

    // The original marker survives — the sweep must not re-stamp a record.
    assertThat(reload(document.getId()).getArchivedAt())
        .isCloseTo(archivedAt, within(1, ChronoUnit.SECONDS));
    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(document.getId()))
        .extracting(AuditEvent::getEventType)
        .doesNotContain(ReviewArchiveService.AUDIT_REVIEW_ARCHIVED);
  }

  @Test
  @DisplayName("the sweep never touches a review that has not been closed at all")
  void sweepSkipsAnOpenReview() throws Exception {
    setRetentionDays(1);
    Document open = documents.save(new Document(MEMBER_ID, "Archive sweep open"));

    mockMvc.perform(as(post(SCHEDULER_RUN), ADMIN_ID)).andExpect(status().isOk());

    Document reloaded = reload(open.getId());
    assertThat(reloaded.getClosedAt()).isNull();
    assertThat(reloaded.getArchivedAt()).isNull();
  }
}
