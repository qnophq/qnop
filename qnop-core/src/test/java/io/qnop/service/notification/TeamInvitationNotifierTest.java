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
package io.qnop.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.NotificationType;
import io.qnop.entity.Team;
import io.qnop.entity.User;
import io.qnop.repository.TeamInvitationRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.TeamInvitationCreated;
import io.qnop.service.mail.MailLocaleResolver;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.review.ReviewNotificationIntent;
import io.qnop.service.review.ReviewNotificationSink;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Who hears about a team invitation, and what they are told (issue #948). */
class TeamInvitationNotifierTest {

  private final TeamRepository teams = mock(TeamRepository.class);
  private final TeamInvitationRepository invitations = mock(TeamInvitationRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ApplicationSettingsService settings = mock(ApplicationSettingsService.class);
  private final MailLocaleResolver locales = mock(MailLocaleResolver.class);
  private final ReviewNotificationSink inApp = mock(ReviewNotificationSink.class);
  private final ReviewNotificationSink mail = mock(ReviewNotificationSink.class);
  private final TeamInvitationNotifier notifier =
      new TeamInvitationNotifier(
          teams, invitations, users, settings, locales, List.of(inApp, mail));

  private final UUID teamId = UUID.randomUUID();
  private final UUID invitee = UUID.randomUUID();
  private final UUID lead = UUID.randomUUID();
  private final TeamInvitationCreated event = new TeamInvitationCreated(teamId, invitee, lead);

  @BeforeEach
  void invitationPending() {
    when(invitations.existsByTeamIdAndUserId(teamId, invitee)).thenReturn(true);
    when(teams.findById(teamId)).thenReturn(Optional.of(Team.create("Legal", null)));
    when(users.findById(invitee))
        .thenReturn(Optional.of(User.internal("Ivy", "ivy@example.com", "ivy", "h")));
    when(users.findById(lead))
        .thenReturn(Optional.of(User.internal("Lena Lead", "lena@example.com", "lena", "h")));
    when(locales.forUser(invitee)).thenReturn("de");
    when(settings.getString(ApplicationSettingKey.GENERAL_BASE_URL))
        .thenReturn("https://qnop.example/");
    when(settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME)).thenReturn("qnop");
    when(inApp.accepts(any())).thenReturn(true);
    when(mail.accepts(any())).thenReturn(true);
  }

  @Test
  @DisplayName("the invited user gets one intent per channel, linking to My Teams")
  void notifiesTheInvitedUser() {
    notifier.dispatch(event);

    ArgumentCaptor<ReviewNotificationIntent> intent =
        ArgumentCaptor.forClass(ReviewNotificationIntent.class);
    verify(inApp).deliver(intent.capture());
    verify(mail).deliver(any());
    ReviewNotificationIntent sent = intent.getValue();
    assertThat(sent.type()).isEqualTo(NotificationType.TEAM_INVITATION);
    assertThat(sent.template()).isEqualTo(MailTemplateKey.TEAM_INVITATION);
    assertThat(sent.actorId()).isEqualTo(lead);
    assertThat(sent.documentId()).isNull();
    assertThat(sent.excerpt()).isEqualTo("Legal");
    assertThat(sent.mailLocale()).isEqualTo("de");
    assertThat(sent.mailVars())
        .containsEntry("actorName", "Lena Lead")
        .containsEntry("teamName", "Legal")
        .containsEntry("recipientName", "Ivy")
        .containsEntry("actionUrl", "https://qnop.example/my-teams");
    // Strict Mustache drops a mail whose variables miss a placeholder.
    assertThat(sent.mailVars().keySet()).containsAll(sent.template().placeholders());
  }

  @Test
  @DisplayName("an invitation already answered or revoked notifies nobody")
  void goneInvitationIsSilent() {
    when(invitations.existsByTeamIdAndUserId(teamId, invitee)).thenReturn(false);

    notifier.dispatch(event);

    verify(inApp, never()).deliver(any());
    verify(mail, never()).deliver(any());
  }

  @Test
  @DisplayName("a disabled invitee or team notifies nobody")
  void disabledTargetsAreSilent() {
    Team disabled = Team.create("Legal", null);
    disabled.setEnabled(false);
    when(teams.findById(teamId)).thenReturn(Optional.of(disabled));

    notifier.dispatch(event);

    verify(inApp, never()).deliver(any());
  }

  @Test
  @DisplayName("a channel that refuses or fails costs the other nothing")
  void channelsAreIndependent() {
    when(mail.accepts(any())).thenReturn(false);
    doThrow(new IllegalStateException("db down")).when(inApp).deliver(any());

    notifier.dispatch(event);

    verify(mail, never()).deliver(any());

    when(mail.accepts(any())).thenReturn(true);
    notifier.dispatch(event);
    verify(mail).deliver(any());
  }
}
