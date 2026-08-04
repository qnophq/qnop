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

import io.qnop.entity.NotificationType;
import io.qnop.entity.User;
import io.qnop.repository.UserSettingRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserSettingKey;
import io.qnop.service.mail.MailService;
import io.qnop.service.notification.ReviewMailCadence;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The e-mail channel (issue #316), now one sink among others (issue #538, ADR-0051).
 *
 * <p>Everything e-mail-specific lives here and only here: the instance-wide review-mail switch, the
 * need for an actual address, and the two per-user opt-outs. That is deliberate — an inbox exists
 * so qnop is usable <em>without</em> living in your mailbox, so muting mail must not mute the
 * in-app record. Moving these gates out of the resolver is what makes that true.
 */
@Component
public class MailNotificationSink implements ReviewNotificationSink {

  private final UserSettingRepository userSettings;
  private final ApplicationSettingsService settings;
  private final MailService mail;

  public MailNotificationSink(
      UserSettingRepository userSettings, ApplicationSettingsService settings, MailService mail) {
    this.userSettings = userSettings;
    this.settings = settings;
    this.mail = mail;
  }

  @Override
  public boolean accepts(ReviewNotificationIntent intent) {
    if (!settings.getBoolean(ApplicationSettingKey.NOTIFICATIONS_REVIEW_EMAILS_ENABLED)) {
      return false;
    }
    User recipient = intent.recipient();
    if (recipient.getEmail() == null || recipient.getEmail().isBlank()) {
      return false;
    }
    return !optedOut(recipient.getId(), intent.type());
  }

  @Override
  public void deliver(ReviewNotificationIntent intent) {
    mail.sendMailFromTemplate(
        intent.template(), intent.recipient().getEmail(), intent.mailVars(), null);
  }

  /**
   * Mentions carry their own opt-out (issue #462) — muting "someone replied" must not also mute
   * "someone named you"; everything else follows the general review-mail opt-out.
   */
  private boolean optedOut(UUID userId, NotificationType type) {
    if (type == NotificationType.MENTION) {
      // Still a plain opt-out: being named personally is the case where latency
      // actually costs something, so a mention is never held for a digest.
      return userSettings
          .findByUserIdAndSettingKey(userId, UserSettingKey.EMAIL_MENTIONS.getKey())
          .map(setting -> "false".equalsIgnoreCase(setting.getSettingValue()))
          .orElse(false);
    }
    // Everything else follows the cadence (issue #680). This sink is the
    // immediate channel, so it stays silent for DAILY — the digest job picks
    // those up from the notification rows the in-app sink writes regardless.
    return cadenceFor(userId) != ReviewMailCadence.IMMEDIATE;
  }

  /** The recipient's chosen cadence, or the registry default where they never chose. */
  private ReviewMailCadence cadenceFor(UUID userId) {
    return userSettings
        .findByUserIdAndSettingKey(userId, UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS.getKey())
        .flatMap(setting -> ReviewMailCadence.parse(setting.getSettingValue()))
        .orElseGet(ReviewMailCadence::registryDefault);
  }
}
