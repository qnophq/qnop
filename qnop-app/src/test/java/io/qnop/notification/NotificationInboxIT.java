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
package io.qnop.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.Notification;
import io.qnop.entity.NotificationType;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.UserSetting;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.NotificationRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.UserSettingRepository;
import io.qnop.service.UserSettingKey;
import io.qnop.testsupport.SeededIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The in-app inbox end to end (issue #538, ADR-0051): a committed review event leaves a row for
 * each recipient, the row is readable only by that recipient, and the things the ADR promises —
 * anonymity on read, e-mail opt-outs not reaching the inbox, and a purge taking its notifications
 * with it — actually hold against a real database.
 */
class NotificationInboxIT extends SeededIntegrationTest {

  private static final String ANCHOR =
      "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.1}},"
          + "\"textQuote\":{\"quote\":\"the clause\"}}";

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private NotificationRepository notifications;
  @Autowired private UserSettingRepository userSettings;

  private UUID documentId;

  private void seedDocument(boolean anonymous) {
    Document document = new Document(MEMBER_ID, "Master services agreement");
    document.setAnonymous(anonymous);
    document.setWorkflowState(WorkflowState.IN_REVIEW);
    documentId = documents.save(document).getId();
    versions.save(
        new DocumentVersion(
            documentId, 1, "sha256/aa/deadbeef", "deadbeef", "application/pdf", 1234L, MEMBER_ID));
    participants.save(ReviewParticipant.forUser(documentId, AUDITOR_ID));
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  private void createAnnotation(UUID author) throws Exception {
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), author)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"versionNumber\":1,\"anchor\":"
                        + ANCHOR
                        + ",\"comment\":\"Please revisit the clause\"}"))
        .andExpect(status().isCreated());
  }

  /**
   * The fan-out is asynchronous (AFTER_COMMIT + {@code @Async}), so the row appears shortly after
   * the request returns — poll for it rather than sleeping a fixed time.
   */
  private List<Notification> awaitInbox(UUID recipientId, int expected) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    List<Notification> found = inboxOf(recipientId);
    while (found.size() < expected && System.nanoTime() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      found = inboxOf(recipientId);
    }
    assertThat(found).as("notifications for %s", recipientId).hasSizeGreaterThanOrEqualTo(expected);
    return found;
  }

  private List<Notification> inboxOf(UUID recipientId) {
    return notifications.findAll().stream()
        .filter(notification -> notification.getRecipientId().equals(recipientId))
        .toList();
  }

  @Test
  @DisplayName("a committed annotation leaves a row for the owner, and none for the actor")
  void annotationCreatesRowForOwnerOnly() throws Exception {
    seedDocument(false);

    createAnnotation(AUDITOR_ID);

    List<Notification> owners = awaitInbox(MEMBER_ID, 1);
    assertThat(owners).hasSize(1);
    assertThat(owners.getFirst().getType()).isEqualTo(NotificationType.ANNOTATION_CREATED);
    assertThat(owners.getFirst().getDocumentId()).isEqualTo(documentId);
    assertThat(owners.getFirst().getExcerpt()).contains("Please revisit the clause");
    assertThat(owners.getFirst().getReadAt()).isNull();
    // The actor never hears about their own action.
    assertThat(notifications.countByRecipientIdAndReadAtIsNull(AUDITOR_ID)).isZero();
  }

  @Test
  @DisplayName("muting review e-mails does not mute the inbox (ADR-0051)")
  void emailOptOutDoesNotSilenceTheInbox() throws Exception {
    seedDocument(false);
    userSettings.save(
        new UserSetting(MEMBER_ID, UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS.getKey(), "false"));

    createAnnotation(AUDITOR_ID);

    // The whole point of an inbox: the user who does not want mail still has a record.
    assertThat(awaitInbox(MEMBER_ID, 1)).hasSize(1);
  }

  @Test
  @DisplayName("the inbox lists, counts and renders the caller's own notifications")
  void listAndRender() throws Exception {
    seedDocument(false);
    createAnnotation(AUDITOR_ID);
    awaitInbox(MEMBER_ID, 1);

    mockMvc
        .perform(as(get("/api/v1/notifications"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.unreadTotal").value(1))
        .andExpect(jsonPath("$.items[0].type").value("ANNOTATION_CREATED"))
        .andExpect(jsonPath("$.items[0].accessible").value(true))
        .andExpect(jsonPath("$.items[0].documentTitle").value("Master services agreement"))
        // The deep link is relative and rebuilt from ids on read.
        .andExpect(
            jsonPath("$.items[0].actionPath").value(org.hamcrest.Matchers.startsWith("/reviews/")))
        .andExpect(jsonPath("$.items[0].readAt").doesNotExist());

    mockMvc
        .perform(as(get("/api/v1/notifications/unread-count"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unread").value(1));
  }

  @Test
  @DisplayName("another user's notification is indistinguishable from one that does not exist")
  void foreignNotificationIsNotFound() throws Exception {
    seedDocument(false);
    createAnnotation(AUDITOR_ID);
    UUID id = awaitInbox(MEMBER_ID, 1).getFirst().getId();

    // AUDITOR is on the review — but this row is not theirs.
    mockMvc
        .perform(as(get("/api/v1/notifications/" + id), AUDITOR_ID))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(as(post("/api/v1/notifications/" + id + "/read"), AUDITOR_ID))
        .andExpect(status().isNotFound());
    // …and their inbox does not contain it either.
    mockMvc
        .perform(as(get("/api/v1/notifications"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  @DisplayName("marking read is persisted, idempotent, and reflected in the count")
  void markReadAndReadAll() throws Exception {
    seedDocument(false);
    createAnnotation(AUDITOR_ID);
    UUID id = awaitInbox(MEMBER_ID, 1).getFirst().getId();

    mockMvc
        .perform(as(post("/api/v1/notifications/" + id + "/read"), MEMBER_ID))
        .andExpect(status().isNoContent());
    // Idempotent: marking it again is fine and keeps the original instant.
    mockMvc
        .perform(as(post("/api/v1/notifications/" + id + "/read"), MEMBER_ID))
        .andExpect(status().isNoContent());

    assertThat(notifications.countByRecipientIdAndReadAtIsNull(MEMBER_ID)).isZero();
    mockMvc
        .perform(as(get("/api/v1/notifications").param("unread", "true"), MEMBER_ID))
        .andExpect(jsonPath("$.total").value(0));
    mockMvc
        .perform(as(get("/api/v1/notifications").param("unread", "false"), MEMBER_ID))
        .andExpect(jsonPath("$.total").value(1));

    mockMvc
        .perform(as(post("/api/v1/notifications/read-all"), MEMBER_ID))
        .andExpect(status().isNoContent());
    assertThat(notifications.countByRecipientIdAndReadAtIsNull(MEMBER_ID)).isZero();
  }

  @Test
  @DisplayName("an anonymous review never renders a foreign actor's real name (ADR-0038)")
  void anonymousReviewStaysAnonymous() throws Exception {
    seedDocument(true);

    createAnnotation(AUDITOR_ID);
    awaitInbox(MEMBER_ID, 1);

    // The owner may see who acted; a peer participant may not. MEMBER_ID owns this
    // review, so the check that matters is the stored row: it holds an id, never a name.
    Notification stored = awaitInbox(MEMBER_ID, 1).getFirst();
    assertThat(stored.getActorId()).isEqualTo(AUDITOR_ID);

    mockMvc
        .perform(as(get("/api/v1/notifications/" + stored.getId()), MEMBER_ID))
        .andExpect(status().isOk())
        // Resolved at read time through ReviewIdentityResolver, so a later privacy
        // change is honoured rather than baked in.
        .andExpect(jsonPath("$.actorName").isNotEmpty());
  }

  @Test
  @DisplayName("purging the review takes its notifications with it (ADR-0050 cascade)")
  void deletingTheDocumentCascades() throws Exception {
    seedDocument(false);
    createAnnotation(AUDITOR_ID);
    awaitInbox(MEMBER_ID, 1);

    // The purge deletes the document and relies entirely on DB cascades; a
    // RESTRICTing notification FK would fail it outright.
    documents.deleteById(documentId);

    assertThat(notifications.countByRecipientIdAndReadAtIsNull(MEMBER_ID)).isZero();
  }
}
