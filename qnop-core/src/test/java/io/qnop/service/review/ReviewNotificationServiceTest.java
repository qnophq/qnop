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
package io.qnop.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserSettingRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.mail.MailService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DB-free behavior of {@link ReviewNotificationService} (issue #316): the global kill switch and
 * the text utilities. The recipient/anonymity wiring runs against the real stack in {@code
 * ReviewNotificationIT}.
 */
@ExtendWith(MockitoExtension.class)
class ReviewNotificationServiceTest {

  @Mock private DocumentRepository documents;
  @Mock private AnnotationRepository annotations;
  @Mock private CommentRepository comments;
  @Mock private ReviewParticipantRepository participants;
  @Mock private TeamMembershipRepository teamMembers;
  @Mock private UserRepository users;
  @Mock private UserSettingRepository userSettings;
  @Mock private ApplicationSettingsService settings;
  @Mock private ReviewIdentityResolver identity;
  @Mock private MailService mail;

  private ReviewNotificationService service() {
    return new ReviewNotificationService(
        documents,
        annotations,
        comments,
        participants,
        teamMembers,
        users,
        userSettings,
        settings,
        identity,
        mail);
  }

  @Test
  @DisplayName("the global switch silences every event before any lookup")
  void globalSwitchOff() {
    when(settings.getBoolean(ApplicationSettingKey.NOTIFICATIONS_REVIEW_EMAILS_ENABLED))
        .thenReturn(false);

    service()
        .dispatch(
            new ReviewEvent.AnnotationCreated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

    verifyNoInteractions(mail, documents, users);
  }

  @Test
  @DisplayName("a vanished document is quietly nothing to say")
  void vanishedDocument() {
    when(settings.getBoolean(ApplicationSettingKey.NOTIFICATIONS_REVIEW_EMAILS_ENABLED))
        .thenReturn(true);
    when(documents.findById(any())).thenReturn(java.util.Optional.empty());

    service()
        .dispatch(
            new ReviewEvent.AnnotationCreated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

    verifyNoInteractions(mail);
  }

  @Test
  @DisplayName("excerpt flattens markdown and caps the length")
  void excerptFlattens() {
    assertThat(ReviewNotificationService.excerpt("**Bold** and [a link](https://x) `code`"))
        .isEqualTo("Bold and a link code");
    assertThat(ReviewNotificationService.excerpt("![img](https://x/pic.png) after"))
        .isEqualTo("after");
    assertThat(ReviewNotificationService.excerpt("line\none\n\n> quoted"))
        .isEqualTo("line one quoted");
    assertThat(ReviewNotificationService.excerpt(null)).isEmpty();
    String long200 = "x".repeat(200);
    assertThat(ReviewNotificationService.excerpt(long200)).hasSize(140).endsWith("…");
  }

  @Test
  @DisplayName("workflow states read like sentences")
  void humanStates() {
    assertThat(ReviewNotificationService.humanState("CHANGES_REQUESTED"))
        .isEqualTo("Changes requested");
    assertThat(ReviewNotificationService.humanState("IN_REVIEW")).isEqualTo("In review");
    assertThat(ReviewNotificationService.humanState("FINALIZED")).isEqualTo("Finalized");
    assertThat(ReviewNotificationService.humanState(null)).isEmpty();
  }
}
