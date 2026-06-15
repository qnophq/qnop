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
package io.qnop.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.qnop.entity.User;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserNotFoundException;
import io.qnop.service.UserService;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailService.SendResult;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminPasswordResetServiceTest {

  @Mock private UserService userService;
  @Mock private PasswordResetTokenService resetTokens;
  @Mock private MailService mailService;
  @Mock private ApplicationSettingsService settings;

  private AdminPasswordResetService service;
  private final UUID actor = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new AdminPasswordResetService(userService, resetTokens, mailService, settings);
    lenient()
        .when(settings.getString(ApplicationSettingKey.GENERAL_BASE_URL))
        .thenReturn("https://qnop.example");
    lenient()
        .when(settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME))
        .thenReturn("qnop");
  }

  @Test
  @DisplayName("rejects an admin resetting their own account")
  void rejectsSelfReset() {
    assertThatThrownBy(() -> service.trigger(actor, actor))
        .isInstanceOf(AdminPasswordResetService.SelfResetNotAllowedException.class);
  }

  @Test
  @DisplayName("rejects resetting an external (OIDC) user")
  void rejectsExternalUser() {
    when(userService.findById(target))
        .thenReturn(Optional.of(User.external("Ext", "ext@example.com")));

    assertThatThrownBy(() -> service.trigger(target, actor))
        .isInstanceOf(AdminPasswordResetService.ExternalUserResetNotAllowedException.class);
  }

  @Test
  @DisplayName("throws when the target user does not exist")
  void throwsWhenUnknown() {
    when(userService.findById(target)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.trigger(target, actor))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  @DisplayName("emails the reset link and reports no fallback URL when SMTP succeeds")
  void emailsWhenSmtpUp() {
    User user = User.internal("Jane", "jane@example.com", "jane", "hash");
    when(userService.findById(target)).thenReturn(Optional.of(user));
    when(resetTokens.issue(user))
        .thenReturn(
            new PasswordResetTokenService.IssuedResetToken("RAW", Instant.now().plusSeconds(900)));
    when(mailService.sendMailFromTemplate(any(), eq("jane@example.com"), anyMap(), isNull()))
        .thenReturn(new SendResult.Sent("jane@example.com"));

    AdminPasswordResetService.Result result = service.trigger(target, actor);

    assertThat(result.tokenIssued()).isTrue();
    assertThat(result.emailSent()).isTrue();
    assertThat(result.resetUrl()).isNull();
  }

  @Test
  @DisplayName("returns the reset URL as a fallback when SMTP delivery fails")
  void fallbackWhenSmtpDown() {
    User user = User.internal("Jane", "jane@example.com", "jane", "hash");
    when(userService.findById(target)).thenReturn(Optional.of(user));
    when(resetTokens.issue(user))
        .thenReturn(
            new PasswordResetTokenService.IssuedResetToken("RAW", Instant.now().plusSeconds(900)));
    when(mailService.sendMailFromTemplate(any(), eq("jane@example.com"), anyMap(), isNull()))
        .thenReturn(new SendResult.Skipped("SMTP is not configured"));

    AdminPasswordResetService.Result result = service.trigger(target, actor);

    assertThat(result.emailSent()).isFalse();
    assertThat(result.resetUrl()).contains("/reset-password?token=RAW");
  }
}
