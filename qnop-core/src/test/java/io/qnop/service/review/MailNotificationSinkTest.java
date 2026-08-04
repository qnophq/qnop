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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.NotificationType;
import io.qnop.entity.User;
import io.qnop.entity.UserSetting;
import io.qnop.repository.UserSettingRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserSettingKey;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The gates that are e-mail's own business (issue #538, ADR-0051) and therefore live in the sink
 * rather than in the resolver: the instance-wide switch, the need for an address, and the two
 * per-user opt-outs. Each of these used to suppress the notification entirely; keeping them here is
 * what lets a user who muted mail still get the in-app record.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailNotificationSinkTest {

  private static final UUID RECIPIENT_ID = UUID.randomUUID();

  @Mock private UserSettingRepository userSettings;
  @Mock private ApplicationSettingsService settings;
  @Mock private MailService mail;

  private MailNotificationSink sink() {
    return new MailNotificationSink(userSettings, settings, mail);
  }

  private ReviewNotificationIntent intent(NotificationType type, String email) {
    User recipient = mock(User.class);
    when(recipient.getId()).thenReturn(RECIPIENT_ID);
    when(recipient.getEmail()).thenReturn(email);
    return ReviewNotificationIntent.to(recipient, type, MailTemplateKey.REVIEW_COMMENT_ADDED)
        .build();
  }

  private void emailsEnabled(boolean enabled) {
    when(settings.getBoolean(ApplicationSettingKey.NOTIFICATIONS_REVIEW_EMAILS_ENABLED))
        .thenReturn(enabled);
  }

  private void optOut(UserSettingKey key) {
    stored(key, "false");
  }

  /** Stores a raw setting value for the recipient, as the registry would hold it. */
  private void stored(UserSettingKey key, String value) {
    UserSetting setting = mock(UserSetting.class);
    when(setting.getSettingValue()).thenReturn(value);
    when(userSettings.findByUserIdAndSettingKey(RECIPIENT_ID, key.getKey()))
        .thenReturn(Optional.of(setting));
  }

  @Test
  @DisplayName("the instance-wide switch silences the mail channel")
  void globalSwitchOff() {
    emailsEnabled(false);

    assertThat(sink().accepts(intent(NotificationType.COMMENT_ADDED, "a@qnop.test"))).isFalse();
  }

  @Test
  @DisplayName("a recipient without an address is not mailable")
  void noAddress() {
    emailsEnabled(true);
    when(userSettings.findByUserIdAndSettingKey(any(), any())).thenReturn(Optional.empty());

    assertThat(sink().accepts(intent(NotificationType.COMMENT_ADDED, null))).isFalse();
    assertThat(sink().accepts(intent(NotificationType.COMMENT_ADDED, "  "))).isFalse();
  }

  @Test
  @DisplayName("the review opt-out silences replies but not mentions (#462)")
  void reviewOptOutLeavesMentionsAlone() {
    emailsEnabled(true);
    when(userSettings.findByUserIdAndSettingKey(any(), any())).thenReturn(Optional.empty());
    optOut(UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS);

    assertThat(sink().accepts(intent(NotificationType.COMMENT_ADDED, "a@qnop.test"))).isFalse();
    assertThat(sink().accepts(intent(NotificationType.MENTION, "a@qnop.test"))).isTrue();
  }

  @Test
  @DisplayName("the mention opt-out silences mentions but not replies (#462)")
  void mentionOptOutLeavesRepliesAlone() {
    emailsEnabled(true);
    when(userSettings.findByUserIdAndSettingKey(any(), any())).thenReturn(Optional.empty());
    optOut(UserSettingKey.EMAIL_MENTIONS);

    assertThat(sink().accepts(intent(NotificationType.MENTION, "a@qnop.test"))).isFalse();
    // …and the dispatcher then offers the reply candidate instead, which is the
    // fall-through ReviewNotificationServiceTest pins down. It needs IMMEDIATE
    // now, since the cadence default no longer mails per event (#680).
    stored(UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS, "IMMEDIATE");
    assertThat(sink().accepts(intent(NotificationType.COMMENT_ADDED, "a@qnop.test"))).isTrue();
  }

  @Test
  @DisplayName("a recipient who chose IMMEDIATE is mailed per event")
  void immediateRecipient() {
    emailsEnabled(true);
    when(userSettings.findByUserIdAndSettingKey(any(), any())).thenReturn(Optional.empty());
    stored(UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS, "IMMEDIATE");

    assertThat(sink().accepts(intent(NotificationType.COMMENT_ADDED, "a@qnop.test"))).isTrue();
  }

  @Test
  @DisplayName("DAILY leaves this sink silent — the digest carries it instead (#680)")
  void dailyIsNotThisSinksBusiness() {
    emailsEnabled(true);
    when(userSettings.findByUserIdAndSettingKey(any(), any())).thenReturn(Optional.empty());
    stored(UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS, "DAILY");

    assertThat(sink().accepts(intent(NotificationType.COMMENT_ADDED, "a@qnop.test"))).isFalse();
    // The in-app sink still writes the row, which is what the digest reads, and a
    // mention still goes out at once — it is not part of the cadence.
    assertThat(sink().accepts(intent(NotificationType.MENTION, "a@qnop.test"))).isTrue();
  }

  @Test
  @DisplayName("a recipient who never chose gets the registry default, which is DAILY")
  void defaultIsDaily() {
    emailsEnabled(true);
    when(userSettings.findByUserIdAndSettingKey(any(), any())).thenReturn(Optional.empty());

    // The visible half of issue #680: an account that never touched this setting
    // stops getting a mail per event.
    assertThat(sink().accepts(intent(NotificationType.COMMENT_ADDED, "a@qnop.test"))).isFalse();
  }

  @Test
  @DisplayName("the legacy booleans still read correctly before the migration runs")
  void legacyValues() {
    emailsEnabled(true);
    when(userSettings.findByUserIdAndSettingKey(any(), any())).thenReturn(Optional.empty());

    stored(UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS, "true");
    assertThat(sink().accepts(intent(NotificationType.COMMENT_ADDED, "a@qnop.test"))).isFalse();

    stored(UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS, "false");
    assertThat(sink().accepts(intent(NotificationType.COMMENT_ADDED, "a@qnop.test"))).isFalse();
  }
}
