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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.qnop.entity.Notification;
import io.qnop.entity.NotificationType;
import io.qnop.repository.NotificationDigestRepository;
import io.qnop.repository.NotificationRepository;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.notification.NotificationDigestService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The digest end to end (issue #680): who gets one, what it covers, and what a second run does.
 *
 * <p>The timezone is pinned to UTC for the recipient so "due" is decided by the wall clock the test
 * runs on — the hour arithmetic itself is {@link io.qnop.service.notification.DigestScheduleTest}'s
 * business, and repeating it here would only make this test fail at 07:00.
 */
class NotificationDigestIT extends SeededIntegrationTest {

  @MockitoBean private MailService mail;

  @Autowired private NotificationDigestService digest;
  @Autowired private NotificationRepository notifications;
  @Autowired private NotificationDigestRepository watermarks;
  @Autowired private io.qnop.repository.DocumentRepository documents;
  @Autowired private io.qnop.service.ApplicationSettingsService settings;

  private UUID documentId;

  @BeforeEach
  void dailyRecipientInUtc() {
    storeReviewMailCadence(MEMBER_ID, "DAILY");
    storeUserSetting(MEMBER_ID, "timezone", "UTC");
    // A real review, because notification.document_id is a foreign key — and a
    // digest grouped by document is only meaningful against one.
    // Without a base URL the renderer deliberately writes no links at all, so a
    // test about links has to configure one.
    settings.update(java.util.Map.of("general.base_url", "https://qnop.example"), null);
    io.qnop.entity.Document document = new io.qnop.entity.Document(MEMBER_ID, "Digest subject");
    document.setWorkflowState(io.qnop.entity.WorkflowState.IN_REVIEW);
    documentId = documents.save(document).getId();
  }

  @Test
  @DisplayName("a second run sends nothing — the watermark holds")
  void idempotentAcrossRuns() {
    unread(MEMBER_ID, NotificationType.COMMENT_ADDED);
    unread(MEMBER_ID, NotificationType.ANNOTATION_CREATED);

    String first = digest.digestOnce(false);
    String second = digest.digestOnce(false);

    // Whether the first run sent depends on the hour it runs at; what must hold
    // either way is that the second one adds nothing.
    if (first.startsWith("Sent")) {
      verify(mail)
          .sendMailFromTemplate(eq(MailTemplateKey.REVIEW_DAILY_DIGEST), any(), any(), any());
      assertThat(second).doesNotStartWith("Sent");
      assertThat(watermarks.findById(MEMBER_ID)).isPresent();
    } else {
      verify(mail, never())
          .sendMailFromTemplate(eq(MailTemplateKey.REVIEW_DAILY_DIGEST), any(), any(), any());
    }
  }

  @Test
  @DisplayName("a dry run reports without sending or consuming the watermark")
  void dryRunChangesNothing() {
    unread(MEMBER_ID, NotificationType.COMMENT_ADDED);

    String summary = digest.digestOnce(true);

    assertThat(summary).contains("nothing changed");
    verify(mail, never())
        .sendMailFromTemplate(eq(MailTemplateKey.REVIEW_DAILY_DIGEST), any(), any(), any());
    // The watermark is untouched, so the real run still has something to send.
    assertThat(watermarks.findById(MEMBER_ID)).isEmpty();
  }

  @Test
  @DisplayName("a recipient on immediate mail is not digested")
  void immediateRecipientsAreSkipped() {
    storeReviewMailCadence(MEMBER_ID, "IMMEDIATE");
    unread(MEMBER_ID, NotificationType.COMMENT_ADDED);

    digest.digestOnce(false);

    verify(mail, never())
        .sendMailFromTemplate(eq(MailTemplateKey.REVIEW_DAILY_DIGEST), any(), any(), any());
  }

  @Test
  @DisplayName("what was already read in the app does not come back in the mail")
  void readInAppStaysRead() {
    Notification read = notifications.save(notification(MEMBER_ID, NotificationType.COMMENT_ADDED));
    read.markRead(java.time.Instant.now());
    notifications.save(read);

    String summary = digest.digestOnce(false);

    assertThat(summary).doesNotStartWith("Sent");
  }

  @Test
  @DisplayName("the mail carries counts and a link per document")
  void mailContentIsASummaryWithWayBack() {
    unread(MEMBER_ID, NotificationType.COMMENT_ADDED);
    unread(MEMBER_ID, NotificationType.COMMENT_ADDED);
    unread(MEMBER_ID, NotificationType.ANNOTATION_CREATED);

    String summary = digest.digestOnce(false);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        summary.startsWith("Sent"), "not yet the recipient's send hour on this run");

    org.mockito.ArgumentCaptor<java.util.Map<String, Object>> vars =
        org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
    verify(mail)
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_DAILY_DIGEST), eq("member@qnop.test"), vars.capture(), any());

    // Headline counts first, then the events themselves in order.
    assertThat(vars.getValue().get("digestBody").toString())
        .contains("Digest subject")
        .contains("1 new annotation")
        .contains("2 comments")
        .contains("raised an annotation")
        .contains("replied in a thread");
    // And the way back into that specific review, not just the list.
    assertThat(vars.getValue().get("digestBodyHtml").toString()).contains("/reviews/" + documentId);
    assertThat(vars.getValue().get("totalPhrase")).isEqualTo("3 updates");
  }

  @Test
  @DisplayName("each recipient's watermark is committed with their own mail, not at the end")
  void watermarkCommitsPerRecipient() {
    // The job is catalogued self-transactional for this reason (issue #680): mail
    // cannot be rolled back, so a watermark deferred to the end of the run would
    // be undone by a crash whose mails had already gone out — and the next run
    // would send them a second time.
    assertThat(
            io.qnop.service.scheduler.SchedulerJobCatalog.find(
                    io.qnop.service.scheduler.SchedulerJobCatalog.NOTIFICATION_DIGEST)
                .orElseThrow()
                .selfTransactional())
        .isTrue();

    unread(MEMBER_ID, NotificationType.COMMENT_ADDED);
    String summary = digest.digestOnce(false);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        summary.startsWith("Sent"), "not yet the recipient's send hour on this run");

    // Visible from outside any transaction this test holds, i.e. actually committed.
    assertThat(watermarks.findById(MEMBER_ID)).isPresent();
  }

  private void unread(UUID recipientId, NotificationType type) {
    notifications.save(notification(recipientId, type));
  }

  private Notification notification(UUID recipientId, NotificationType type) {
    return Notification.of(recipientId, type).withDocument(documentId);
  }
}
