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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.User;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserService;
import io.qnop.service.mail.MailLocaleResolver;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PasswordResetFlowServiceTest {

  @Mock private UserService userService;
  @Mock private PasswordResetTokenService resetTokens;
  @Mock private MailService mailService;
  @Mock private ApplicationSettingsService settings;
  @Mock private MailLocaleResolver mailLocales;

  private PasswordResetFlowService service;

  @BeforeEach
  void setUp() {
    service =
        new PasswordResetFlowService(userService, resetTokens, mailService, settings, mailLocales);
    lenient()
        .when(settings.getString(ApplicationSettingKey.GENERAL_BASE_URL))
        .thenReturn("https://qnop.example");
    lenient()
        .when(settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME))
        .thenReturn("qnop");
    lenient().when(mailLocales.forUser(any())).thenReturn("en");
  }

  @Test
  @DisplayName("requestReset does nothing when password reset is disabled")
  void requestResetDisabled() {
    when(settings.getBoolean(ApplicationSettingKey.AUTH_PASSWORD_RESET_ENABLED)).thenReturn(false);

    service.requestReset("jane@example.com");

    verify(userService, never()).findInternalByEmail(any());
    verify(resetTokens, never()).issue(any());
  }

  @Test
  @DisplayName("requestReset issues a token and emails the link for an enabled local account")
  void requestResetEnabled() {
    User user = User.internal("Jane", "jane@example.com", "jane", "hash");
    when(settings.getBoolean(ApplicationSettingKey.AUTH_PASSWORD_RESET_ENABLED)).thenReturn(true);
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(user));
    when(resetTokens.issue(user))
        .thenReturn(
            new PasswordResetTokenService.IssuedResetToken("RAW", Instant.now().plusSeconds(60)));

    service.requestReset("jane@example.com");

    verify(mailService)
        .sendMailFromTemplate(
            eq(MailTemplateKey.PASSWORD_RESET), eq("jane@example.com"), anyMap(), eq("en"));
  }

  @Test
  @DisplayName("the reset mail and its expiry phrase use the recipient's mail language (#853)")
  void resetMailUsesTheRecipientsLanguage() {
    User user = User.internal("Jane", "jane@example.com", "jane", "hash");
    when(mailLocales.forUser(user.getId())).thenReturn("de");
    when(resetTokens.issue(user))
        .thenReturn(
            new PasswordResetTokenService.IssuedResetToken(
                "RAW", Instant.now().plusSeconds(24 * 3600)));

    service.sendSetupLink(user, MailTemplateKey.ADMIN_PASSWORD_RESET);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mailService)
        .sendMailFromTemplate(
            eq(MailTemplateKey.ADMIN_PASSWORD_RESET),
            eq("jane@example.com"),
            vars.capture(),
            eq("de"));
    assertThat(vars.getValue()).containsEntry("expiresAtHuman", "in 24 Stunden");
  }

  @Test
  @DisplayName("requestReset skips a disabled (unverified) account")
  void requestResetDisabledUser() {
    User user = User.internal("Jane", "jane@example.com", "jane", "hash");
    user.setEnabled(false);
    when(settings.getBoolean(ApplicationSettingKey.AUTH_PASSWORD_RESET_ENABLED)).thenReturn(true);
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(user));

    service.requestReset("jane@example.com");

    verify(resetTokens, never()).issue(any());
  }

  @Test
  @DisplayName("reset consumes the token and applies the new password")
  void resetApplies() {
    User user = User.internal("Jane", "jane@example.com", "jane", "hash");
    when(resetTokens.consume("tok")).thenReturn(user);

    service.reset("tok", "new-password");

    verify(userService).applyPasswordReset(user.getId(), "new-password");
  }
}
