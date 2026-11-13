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
import io.qnop.service.mail.MailMessages;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.review.ReviewNotificationIntent;
import io.qnop.service.review.ReviewNotificationSink;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells an invited user about a team invitation (issue #948, ADR-0064 amendment) — in the inbox and
 * by e-mail.
 *
 * <p>It reuses the review notifications' delivery channels rather than growing a second set: the
 * resolution here is trivial (exactly one recipient, the invited user), so the intent is built
 * directly and offered to every {@link ReviewNotificationSink}. The in-app sink writes the row; the
 * mail sink applies the invitation's own opt-out ({@code email_team_invitations}). Each channel
 * fails on its own, as for review events.
 *
 * <p>The inviting lead acts under their public name — team membership carries no anonymity — so the
 * actor's display name goes into the mail as is, and the row keeps only the id.
 */
@Service
public class TeamInvitationNotifier {

  private static final Logger log = LoggerFactory.getLogger(TeamInvitationNotifier.class);

  /** The SPA route where invitations are answered. */
  static final String MY_TEAMS_PATH = "/my-teams";

  private final TeamRepository teams;
  private final TeamInvitationRepository invitations;
  private final UserRepository users;
  private final ApplicationSettingsService settings;
  private final MailLocaleResolver mailLocales;
  private final List<ReviewNotificationSink> sinks;

  public TeamInvitationNotifier(
      TeamRepository teams,
      TeamInvitationRepository invitations,
      UserRepository users,
      ApplicationSettingsService settings,
      MailLocaleResolver mailLocales,
      List<ReviewNotificationSink> sinks) {
    this.teams = teams;
    this.invitations = invitations;
    this.users = users;
    this.settings = settings;
    this.mailLocales = mailLocales;
    this.sinks = sinks;
  }

  /**
   * Offers the invitation to every channel; quietly does nothing when there is nobody left to tell
   * — the invitation was answered or revoked before this ran, the team was disabled, or the account
   * is gone or disabled.
   */
  @Transactional(readOnly = true)
  public void dispatch(TeamInvitationCreated event) {
    Optional<ReviewNotificationIntent> intent = resolve(event);
    if (intent.isEmpty()) {
      return;
    }
    for (ReviewNotificationSink sink : sinks) {
      if (sink.accepts(intent.get())) {
        deliverQuietly(sink, intent.get());
      }
    }
  }

  private Optional<ReviewNotificationIntent> resolve(TeamInvitationCreated event) {
    if (event.userId().equals(event.actorId())
        || !invitations.existsByTeamIdAndUserId(event.teamId(), event.userId())) {
      return Optional.empty();
    }
    Optional<Team> team = teams.findById(event.teamId()).filter(Team::isEnabled);
    Optional<User> recipient = users.findById(event.userId()).filter(User::isEnabled);
    if (team.isEmpty() || recipient.isEmpty()) {
      return Optional.empty();
    }
    MailMessages messages = MailMessages.forLocale(mailLocales.forUser(event.userId()));
    String actorName =
        users
            .findById(event.actorId())
            .map(User::getDisplayName)
            .filter(name -> !name.isBlank())
            .orElseGet(() -> messages.get("actor.participant"));
    return Optional.of(
        ReviewNotificationIntent.to(
                recipient.get(), NotificationType.TEAM_INVITATION, MailTemplateKey.TEAM_INVITATION)
            .var("siteName", settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME))
            .var("recipientName", recipient.get().getDisplayName())
            .var("actorName", actorName)
            .var("teamName", team.get().getName())
            .var("actionUrl", myTeamsUrl())
            .mailLocale(messages.locale())
            .actor(event.actorId())
            // The team's name is the excerpt: the row has no team column, and like a deleted
            // review's title it is what the notice is about (ADR-0051 amendment).
            .excerpt(team.get().getName())
            .build());
  }

  private void deliverQuietly(ReviewNotificationSink sink, ReviewNotificationIntent intent) {
    try {
      sink.deliver(intent);
    } catch (RuntimeException ex) {
      log.warn(
          "{} could not deliver a team invitation notification to {}",
          sink.getClass().getSimpleName(),
          intent.recipient().getId(),
          ex);
    }
  }

  private String myTeamsUrl() {
    String base = settings.getString(ApplicationSettingKey.GENERAL_BASE_URL);
    if (base == null || base.isBlank()) {
      log.warn("general.base_url is not configured — the invitation mail link will be relative");
      base = "";
    }
    return base.replaceAll("/+$", "") + MY_TEAMS_PATH;
  }
}
