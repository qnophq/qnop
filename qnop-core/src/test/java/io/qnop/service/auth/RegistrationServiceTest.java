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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserService;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

  @Mock private UserService userService;
  @Mock private EmailVerificationTokenService verificationTokens;
  @Mock private MailService mailService;
  @Mock private ApplicationSettingsService settings;

  private RegistrationService service;

  @BeforeEach
  void setUp() {
    service = new RegistrationService(userService, verificationTokens, mailService, settings);
    lenient()
        .when(settings.getString(ApplicationSettingKey.GENERAL_BASE_URL))
        .thenReturn("https://qnop.example/");
    lenient()
        .when(settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME))
        .thenReturn("qnop");
  }

  @Test
  @DisplayName("anti-enumeration: an existing account is a silent no-op (no create, no email)")
  void existingAccountIsNoOp() {
    when(userService.internalUserExists("jane", "jane@example.com")).thenReturn(true);

    service.register("jane", "jane@example.com", "password1", "Jane");

    verify(userService, never()).createSelfRegistered(any(), any(), any(), any(), any());
    verify(verificationTokens, never()).issue(any());
    verify(mailService, never()).sendMailFromTemplate(any(), any(), anyMap(), any());
  }

  @Test
  @DisplayName(
      "a new account is created with the configured default role, token issued, email sent")
  void newAccountIsRegistered() {
    User user = User.internal("Jane", "jane@example.com", "jane", "hash");
    when(userService.internalUserExists("jane", "jane@example.com")).thenReturn(false);
    when(settings.getString(ApplicationSettingKey.AUTH_SELF_REGISTRATION_DEFAULT_ROLE))
        .thenReturn("AUDITOR");
    when(userService.createSelfRegistered(
            "jane", "jane@example.com", "password1", "Jane", UserRole.AUDITOR))
        .thenReturn(user);
    when(verificationTokens.issue(user))
        .thenReturn(
            new EmailVerificationTokenService.IssuedToken("RAW", Instant.now().plusSeconds(60)));

    service.register("jane", "jane@example.com", "password1", "Jane");

    verify(userService)
        .createSelfRegistered("jane", "jane@example.com", "password1", "Jane", UserRole.AUDITOR);
    verify(verificationTokens).issue(user);
    verify(mailService)
        .sendMailFromTemplate(
            eq(MailTemplateKey.REGISTRATION_VERIFICATION),
            eq("jane@example.com"),
            anyMap(),
            isNull());
  }

  @Test
  @DisplayName("self-registration never mints an ADMIN, even if the setting says so")
  void adminDefaultRoleIsDowngradedToMember() {
    User user = User.internal("Jane", "jane@example.com", "jane", "hash");
    when(userService.internalUserExists("jane", "jane@example.com")).thenReturn(false);
    when(settings.getString(ApplicationSettingKey.AUTH_SELF_REGISTRATION_DEFAULT_ROLE))
        .thenReturn("ADMIN");
    when(userService.createSelfRegistered(
            "jane", "jane@example.com", "password1", "Jane", UserRole.MEMBER))
        .thenReturn(user);
    when(verificationTokens.issue(user))
        .thenReturn(
            new EmailVerificationTokenService.IssuedToken("RAW", Instant.now().plusSeconds(60)));

    service.register("jane", "jane@example.com", "password1", "Jane");

    verify(userService)
        .createSelfRegistered("jane", "jane@example.com", "password1", "Jane", UserRole.MEMBER);
  }

  @Test
  @DisplayName("verify consumes the token and activates the account")
  void verifyActivates() {
    User user = User.internal("Jane", "jane@example.com", "jane", "hash");
    when(verificationTokens.consume("tok")).thenReturn(user);

    service.verify("tok");

    verify(userService).enable(user.getId());
  }
}
