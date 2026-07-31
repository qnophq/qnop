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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.Notification;
import io.qnop.entity.NotificationType;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.NotificationRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.service.review.ReviewDeletionService;
import io.qnop.service.storage.StorageService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Deleting a review permanently (issue #421): admin-only, and it really does take everything.
 *
 * <p>The owner's tool is `archive`, which is reversible; this is the other end of that decision, so
 * the tests are as much about who is refused as about what is destroyed.
 */
class ReviewDeletionIT extends SeededIntegrationTest {

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private AnnotationRepository annotations;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private NotificationRepository notifications;
  @Autowired private StorageService storage;

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  /** A review owned by MEMBER, reviewed by MEMBER2, with one version at {@code storageKey}. */
  private UUID review(String title, String storageKey) {
    Document document = new Document(MEMBER_ID, title);
    document.setWorkflowState(WorkflowState.IN_REVIEW);
    UUID documentId = documents.save(document).getId();
    versions.save(
        new DocumentVersion(documentId, 1, storageKey, "hash", "application/pdf", 4L, MEMBER_ID));
    participants.save(ReviewParticipant.forUser(documentId, MEMBER2_ID));
    return documentId;
  }

  /** Stores a real object so the release can be observed rather than assumed. */
  private String storeObject(String content) {
    var staged =
        storage.stage(
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), "application/pdf");
    storage.commit(staged.key());
    return staged.key();
  }

  @Test
  @DisplayName("an admin deletes a review, and everything it owned goes with it")
  void adminDeletesTheWholeAggregate() throws Exception {
    String key = storeObject("only this review holds these bytes");
    UUID documentId = review("Deleted by an admin", key);
    annotate(documentId, MEMBER_ID);

    mockMvc
        .perform(as(delete("/api/v1/documents/" + documentId), ADMIN_ID))
        .andExpect(status().isNoContent());

    assertThat(documents.findById(documentId)).isEmpty();
    assertThat(versions.findByDocumentIdOrderByVersionNumberAsc(documentId)).isEmpty();
    assertThat(annotations.findByDocumentId(documentId)).isEmpty();
    assertThat(participants.findByDocumentId(documentId)).isEmpty();
    // The blob is gone too, not merely unreferenced — an orphan nobody reclaims
    // would defeat the point of deleting.
    assertThat(storage.get(key)).isEmpty();

    // Even the review itself is gone from the admin's own listing.
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId), ADMIN_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("nobody but an admin may delete — not even the owner")
  void deletionIsAdminOnly() throws Exception {
    UUID documentId = review("Not the owners to destroy", storeObject("kept"));

    // The owner's tool is archive, which is reversible. Deleting takes other
    // people's annotations with it, so it is not theirs alone to decide.
    for (UUID actor : List.of(MEMBER_ID, MEMBER2_ID)) {
      mockMvc
          .perform(as(delete("/api/v1/documents/" + documentId), actor))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // Someone who cannot see it at all learns nothing further: 404, not 403.
    mockMvc
        .perform(as(delete("/api/v1/documents/" + documentId), EXTERNAL_ID))
        .andExpect(status().isNotFound());

    assertThat(documents.findById(documentId)).isPresent();
  }

  @Test
  @DisplayName("a blob two reviews share survives the deletion of one of them")
  void sharedStorageIsKept() throws Exception {
    // Storage keys are content-addressed, so uploading the same file twice yields
    // ONE object. Deleting one review must not empty the other — the check that is
    // easiest to lose when this code moves.
    String shared = storeObject("the very same bytes in two reviews");
    UUID first = review("First review of the same file", shared);
    UUID second = review("Second review of the same file", shared);

    mockMvc
        .perform(as(delete("/api/v1/documents/" + first), ADMIN_ID))
        .andExpect(status().isNoContent());

    assertThat(documents.findById(first)).isEmpty();
    assertThat(documents.findById(second)).isPresent();
    assertThat(storage.get(shared)).isPresent();
  }

  @Test
  @DisplayName("a record of the deletion outlives the review it describes")
  void theAuditTrailRemembers() throws Exception {
    UUID documentId = review("Gone but audited", storeObject("audited"));

    mockMvc
        .perform(as(delete("/api/v1/documents/" + documentId), ADMIN_ID))
        .andExpect(status().isNoContent());

    // The review's own audit rows cascade away with it, so this SYSTEM-scoped one
    // is the only surviving answer to "where did that review go?" — hence the
    // title, and the real actor.
    assertThat(auditEvents.findAll())
        .filteredOn(
            event -> ReviewDeletionService.AUDIT_REVIEW_DELETED.equals(event.getEventType()))
        .isNotEmpty()
        .anySatisfy(
            event -> {
              assertThat(event.getActorId()).isEqualTo(ADMIN_ID);
              assertThat(event.getDetail()).contains("Gone but audited");
              assertThat(event.getDetail()).contains(documentId.toString());
            });
  }

  @Test
  @DisplayName("a draft can be deleted without cancelling and archiving it first")
  void anyStateMayGo() throws Exception {
    Document draft = new Document(MEMBER_ID, "Created by mistake");
    draft.setWorkflowState(WorkflowState.DRAFT);
    UUID documentId = documents.save(draft).getId();

    // The usual case for deleting at all is a review that should never have
    // existed; routing it through cancel and archive would be friction, not safety.
    mockMvc
        .perform(as(delete("/api/v1/documents/" + documentId), ADMIN_ID))
        .andExpect(status().isNoContent());

    assertThat(documents.findById(documentId)).isEmpty();
  }

  @Test
  @DisplayName("deleting the same review twice is a 404, not a server error")
  void secondDeleteIsNotFound() throws Exception {
    UUID documentId = review("Deleted once", storeObject("once"));

    mockMvc
        .perform(as(delete("/api/v1/documents/" + documentId), ADMIN_ID))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(as(delete("/api/v1/documents/" + documentId), ADMIN_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("the owner is told their review is gone, and the notice outlives it")
  void theOwnerIsNotified() throws Exception {
    UUID documentId = review("Deleted out from under its owner", storeObject("notified"));

    mockMvc
        .perform(as(delete("/api/v1/documents/" + documentId), ADMIN_ID))
        .andExpect(status().isNoContent());

    List<Notification> mine =
        notifications.findAll().stream()
            .filter(n -> MEMBER_ID.equals(n.getRecipientId()))
            .filter(n -> n.getType() == NotificationType.REVIEW_DELETED)
            .toList();

    assertThat(mine).hasSize(1);
    // No documentId, deliberately: that column cascades with the document, so a
    // row naming the deleted review would have been deleted in the same
    // statement. The title rides along instead.
    assertThat(mine.getFirst().getDocumentId()).isNull();
    assertThat(mine.getFirst().getExcerpt()).isEqualTo("Deleted out from under its owner");

    // And the inbox says which review, rather than the "no longer available to
    // you" tombstone every other document-less notification gets.
    mockMvc
        .perform(as(get("/api/v1/notifications"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[?(@.type == 'REVIEW_DELETED')].documentTitle")
                .value(org.hamcrest.Matchers.hasItem("Deleted out from under its owner")))
        // Nothing to open: the client hides the link rather than offering a 404.
        .andExpect(
            jsonPath("$.items[?(@.type == 'REVIEW_DELETED')].accessible")
                .value(org.hamcrest.Matchers.hasItem(false)));
  }

  @Test
  @DisplayName("an admin deleting their own review is not notified about it")
  void noSelfNotification() throws Exception {
    Document own = new Document(ADMIN_ID, "The admins own mistake");
    own.setWorkflowState(WorkflowState.DRAFT);
    UUID documentId = documents.save(own).getId();

    mockMvc
        .perform(as(delete("/api/v1/documents/" + documentId), ADMIN_ID))
        .andExpect(status().isNoContent());

    assertThat(notifications.findAll())
        .filteredOn(n -> n.getType() == NotificationType.REVIEW_DELETED)
        .isEmpty();
  }

  /** Raises one annotation on version 1, so the cascade has something to take. */
  private void annotate(UUID documentId, UUID author) throws Exception {
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), author)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"versionNumber\":1,\"anchor\":{\"region\":{\"surfaceIndex\":0,"
                        + "\"box\":{\"x\":0.1,\"y\":0.1,\"width\":0.3,\"height\":0.05}},"
                        + "\"textQuote\":{\"quote\":\"the clause\"}},\"comment\":\"A concern\"}"))
        .andExpect(status().isCreated());
  }
}
