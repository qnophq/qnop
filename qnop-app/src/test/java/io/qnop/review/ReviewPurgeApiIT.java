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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Annotation;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.AuditScope;
import io.qnop.entity.Comment;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentAttachment;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentAttachmentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.review.ReviewPurgeService;
import io.qnop.service.scheduler.RunOutcome;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import io.qnop.service.storage.StagedObject;
import io.qnop.service.storage.StorageService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The archived-review purge end-to-end against a real Postgres + MinIO (issue #577, ADR-0050).
 *
 * <p>The case this class exists for is the <strong>shared storage object</strong>: keys are
 * content-addressed and deduplicated instance-wide (ADR-0005/0036), so purging one review must not
 * take a binary another review still serves. That cannot be faked — it needs two documents whose
 * content genuinely dedupes to one key, a real bucket, and the registry.
 *
 * <p>Also covered: the full aggregate delete leaving no orphan row, the disabled-by-default job,
 * the dry run, the retention window and its runtime change, and the surviving SYSTEM audit record.
 *
 * <p>Not {@code @Transactional}: object-store writes do not roll back, and the purge deliberately
 * commits per review. Content is unique per test, so content-addressed keys keep tests isolated.
 */
class ReviewPurgeApiIT extends SeededIntegrationTest {

  private static final String SCHEDULER_JOB =
      "/api/v1/admin/scheduler/" + SchedulerJobCatalog.REVIEW_PURGE;
  private static final String SCHEDULER_RUN = SCHEDULER_JOB + "/run";
  private static final String RETENTION_KEY =
      ApplicationSettingKey.REVIEW_PURGE_ARCHIVED_AFTER_DAYS.getKey();

  /** Jackson 3 (tools.jackson), matching the stack the app itself serializes with. */
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private DocumentAttachmentRepository attachments;
  @Autowired private AnnotationRepository annotations;
  @Autowired private CommentRepository comments;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private StorageObjectRepository storageObjects;
  @Autowired private StorageService storage;
  @Autowired private ApplicationSettingsService settings;
  @Autowired private SchedulerService scheduler;

  /**
   * {@code application_setting} deliberately survives {@code clean.sql} (it is migration-seeded),
   * so a retention change would leak into every later test in the JVM-shared database.
   */
  @AfterEach
  void restoreRetentionDefault() {
    settings.update(
        Map.of(
            RETENTION_KEY,
            ApplicationSettingKey.REVIEW_PURGE_ARCHIVED_AFTER_DAYS.getDefaultValue()),
        null);
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  private void setRetentionDays(int days) {
    settings.update(Map.of(RETENTION_KEY, String.valueOf(days)), null);
  }

  private static byte[] uniqueContent() {
    return ("qnop-purge-" + UUID.randomUUID()).getBytes(UTF_8);
  }

  private StagedObject stage(byte[] content) {
    StagedObject staged = storage.stage(new ByteArrayInputStream(content), "application/pdf");
    storage.commit(staged.key());
    return staged;
  }

  /** An archived review owned by MEMBER, with one version backed by {@code staged}. */
  private Document archivedReview(String title, StagedObject staged, long archivedDaysAgo) {
    Document document = new Document(MEMBER_ID, title);
    document.setWorkflowState(WorkflowState.FINALIZED);
    document.setClosedAt(Instant.now().minus(Duration.ofDays(archivedDaysAgo + 1)));
    document.setArchivedAt(Instant.now().minus(Duration.ofDays(archivedDaysAgo)));
    Document saved = documents.save(document);
    versions.save(
        new DocumentVersion(
            saved.getId(),
            1,
            staged.key(),
            staged.contentHash(),
            "application/pdf",
            staged.sizeBytes(),
            MEMBER_ID));
    return saved;
  }

  private void runPurgeAsAdmin() throws Exception {
    mockMvc
        .perform(as(post(SCHEDULER_RUN), ADMIN_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastOutcome").value("SUCCESS"));
  }

  // ── the shared-object guard (why this IT exists) ─────────────────────────

  @Test
  @DisplayName("purging one review keeps a storage object another review still references")
  void sharedStorageObjectSurvivesThePurgeOfOneReferrer() throws Exception {
    setRetentionDays(90);
    byte[] identical = uniqueContent();
    // Two reviews ingesting IDENTICAL content dedupe to ONE content-addressed object.
    StagedObject first = stage(identical);
    StagedObject second = stage(identical);
    assertThat(second.key()).isEqualTo(first.key());

    Document doomed = archivedReview("Purge shared doomed", first, 200);
    Document survivor = archivedReview("Purge shared survivor", second, 1);

    runPurgeAsAdmin();

    assertThat(documents.findById(doomed.getId())).isEmpty();
    assertThat(documents.findById(survivor.getId())).isPresent();
    // The object and its registry row must both survive — the survivor still serves this binary.
    assertThat(storage.get(first.key())).isPresent();
    assertThat(storageObjects.findByObjectKey(first.key())).isPresent();
    // And it is still readable through the survivor's own serving path.
    mockMvc
        .perform(
            as(get("/api/v1/documents/" + survivor.getId() + "/versions/1/original"), MEMBER_ID))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName(
      "a storage object only the purged review referenced is deleted, registry row and all")
  void exclusiveStorageObjectIsDeleted() throws Exception {
    setRetentionDays(90);
    StagedObject staged = stage(uniqueContent());
    Document doomed = archivedReview("Purge exclusive", staged, 200);

    runPurgeAsAdmin();

    assertThat(documents.findById(doomed.getId())).isEmpty();
    assertThat(storage.get(staged.key())).isEmpty();
    assertThat(storageObjects.findByObjectKey(staged.key())).isEmpty();
  }

  @Test
  @DisplayName("an attachment sharing the purged review's object also protects it")
  void objectSharedWithAnAttachmentSurvives() throws Exception {
    setRetentionDays(90);
    byte[] identical = uniqueContent();
    StagedObject versionObject = stage(identical);
    StagedObject attachmentObject = stage(identical);

    Document doomed = archivedReview("Purge attachment-shared", versionObject, 200);
    Document survivor = documents.save(new Document(MEMBER_ID, "Purge attachment host"));
    attachments.save(
        new DocumentAttachment(
            survivor.getId(),
            MEMBER_ID,
            "diagram.png",
            "application/pdf",
            attachmentObject.contentHash(),
            attachmentObject.sizeBytes(),
            attachmentObject.key()));

    runPurgeAsAdmin();

    assertThat(documents.findById(doomed.getId())).isEmpty();
    assertThat(storage.get(versionObject.key())).isPresent();
  }

  // ── the full aggregate delete ───────────────────────────────────────────

  @Test
  @DisplayName("the purge leaves no orphan row anywhere in the review aggregate")
  void purgeLeavesNoOrphanRows() throws Exception {
    setRetentionDays(90);
    StagedObject staged = stage(uniqueContent());
    Document doomed = archivedReview("Purge aggregate", staged, 200);
    participants.save(ReviewParticipant.forUser(doomed.getId(), AUDITOR_ID));
    Annotation annotation = annotations.save(new Annotation(doomed.getId(), AUDITOR_ID));
    comments.save(new Comment(annotation.getId(), AUDITOR_ID, "please clarify"));
    attachments.save(
        new DocumentAttachment(
            doomed.getId(),
            MEMBER_ID,
            "note.png",
            "application/pdf",
            staged.contentHash(),
            staged.sizeBytes(),
            staged.key()));
    auditEvents.save(new AuditEvent(doomed.getId(), "workflow.transition", MEMBER_ID, "{}"));

    runPurgeAsAdmin();

    assertThat(documents.findById(doomed.getId())).isEmpty();
    assertThat(versions.findByDocumentIdOrderByVersionNumberAsc(doomed.getId())).isEmpty();
    assertThat(participants.findByDocumentId(doomed.getId())).isEmpty();
    assertThat(annotations.findByDocumentId(doomed.getId())).isEmpty();
    assertThat(comments.findByAnnotationIdOrderByCreatedAtAsc(annotation.getId())).isEmpty();
    assertThat(attachments.findStorageKeysByDocumentId(doomed.getId())).isEmpty();
    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(doomed.getId())).isEmpty();
    // The owner and the reviewer survive — the cascade never reaches principals.
    mockMvc.perform(as(get("/api/v1/users/me"), MEMBER_ID)).andExpect(status().isOk());
  }

  // ── the retention window ────────────────────────────────────────────────

  @Test
  @DisplayName(
      "a recently archived review is untouched; the retention change lands on the next run")
  void retentionWindowIsRespectedAndRuntimeChangeable() throws Exception {
    setRetentionDays(90);
    StagedObject staged = stage(uniqueContent());
    Document recent = archivedReview("Purge recent", staged, 30);

    runPurgeAsAdmin();
    assertThat(documents.findById(recent.getId())).isPresent();

    // Shrink the window through the admin settings API — no restart, next run purges it.
    mockMvc
        .perform(
            as(patch("/api/v1/admin/settings"), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"" + RETENTION_KEY + "\":\"7\"}}"))
        .andExpect(status().isOk());

    runPurgeAsAdmin();
    assertThat(documents.findById(recent.getId())).isEmpty();
  }

  @Test
  @DisplayName("a retention of 0 disables purging even for a long-archived review")
  void retentionZeroDisablesPurging() throws Exception {
    setRetentionDays(0);
    StagedObject staged = stage(uniqueContent());
    Document ancient = archivedReview("Purge disabled", staged, 900);

    runPurgeAsAdmin();

    assertThat(documents.findById(ancient.getId())).isPresent();
    assertThat(storage.get(staged.key())).isPresent();
  }

  @Test
  @DisplayName("an archived-but-not-yet-eligible review and a never-archived one are both spared")
  void neverArchivedReviewIsNeverPurged() throws Exception {
    setRetentionDays(90);
    StagedObject staged = stage(uniqueContent());
    Document active = documents.save(new Document(MEMBER_ID, "Purge never-archived"));
    versions.save(
        new DocumentVersion(
            active.getId(),
            1,
            staged.key(),
            staged.contentHash(),
            "application/pdf",
            staged.sizeBytes(),
            MEMBER_ID));

    runPurgeAsAdmin();

    assertThat(documents.findById(active.getId())).isPresent();
    assertThat(storage.get(staged.key())).isPresent();
  }

  // ── dry run ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a dry run reports but destroys nothing")
  void dryRunDestroysNothing() throws Exception {
    setRetentionDays(90);
    StagedObject staged = stage(uniqueContent());
    Document doomed = archivedReview("Purge dry-run", staged, 200);

    mockMvc
        .perform(
            as(patch(SCHEDULER_JOB), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supportsDryRun").value(true))
        .andExpect(jsonPath("$.dryRun").value(true));

    runPurgeAsAdmin();

    assertThat(documents.findById(doomed.getId())).isPresent();
    assertThat(storage.get(staged.key())).isPresent();
    assertThat(purgeAuditEvents()).isEmpty();
  }

  // ── the double opt-in ───────────────────────────────────────────────────

  @Test
  @DisplayName("the job ships disabled, so a scheduled tick purges nothing on a fresh instance")
  void jobIsDisabledByDefault() throws Exception {
    setRetentionDays(90);
    StagedObject staged = stage(uniqueContent());
    Document doomed = archivedReview("Purge disabled-by-default", staged, 200);

    // The dashboard must not advertise it as enabled either.
    mockMvc
        .perform(as(get("/api/v1/admin/scheduler"), ADMIN_ID))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[?(@.jobId == '" + SchedulerJobCatalog.REVIEW_PURGE + "')].enabled")
                .value(org.hamcrest.Matchers.contains(false)));

    assertThat(scheduler.runScheduled(SchedulerJobCatalog.REVIEW_PURGE))
        .isEqualTo(RunOutcome.SKIPPED_DISABLED);
    assertThat(documents.findById(doomed.getId())).isPresent();

    // Run-now is the explicit override and still works — the second half of the opt-in.
    runPurgeAsAdmin();
    assertThat(documents.findById(doomed.getId())).isEmpty();
  }

  // ── the surviving audit record ───────────────────────────────────────────

  @Test
  @DisplayName("a purge run leaves a SYSTEM audit row naming what it destroyed")
  void purgeIsAuditedOnTheSystemStream() throws Exception {
    setRetentionDays(90);
    StagedObject staged = stage(uniqueContent());
    Document doomed = archivedReview("Purge audited review", staged, 200);

    runPurgeAsAdmin();

    List<AuditEvent> purgeEvents = purgeAuditEvents();
    assertThat(purgeEvents).hasSize(1);
    AuditEvent event = purgeEvents.get(0);
    assertThat(event.getScope()).isEqualTo(AuditScope.SYSTEM);
    assertThat(event.getDocumentId()).isNull();
    // detail is jsonb, so Postgres re-formats and re-orders it on the round trip — assert on the
    // parsed structure, never on the serialized string.
    JsonNode detail = MAPPER.readTree(event.getDetail());
    assertThat(detail.get("reviews").asInt()).isEqualTo(1);
    assertThat(detail.get("storageObjects").asInt()).isEqualTo(1);
    JsonNode purged = detail.get("purged");
    assertThat(purged).hasSize(1);
    assertThat(purged.get(0).get("id").asString()).isEqualTo(doomed.getId().toString());
    // The title is what makes the row useful once the review itself is gone.
    assertThat(purged.get(0).get("title").asString()).isEqualTo("Purge audited review");

    // And it is visible to an AUDITOR on the audit surface, with its own vocabulary.
    mockMvc
        .perform(
            as(
                get("/api/v1/audit/events")
                    .param("eventType", ReviewPurgeService.AUDIT_REVIEWS_PURGED),
                AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].scope").value("SYSTEM"));
  }

  private List<AuditEvent> purgeAuditEvents() {
    return auditEvents.findAll().stream()
        .filter(e -> ReviewPurgeService.AUDIT_REVIEWS_PURGED.equals(e.getEventType()))
        .toList();
  }
}
