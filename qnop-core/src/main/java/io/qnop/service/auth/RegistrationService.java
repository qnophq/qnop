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

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserService;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates self-registration and email verification (issue #20): create-or-skip, token issue,
 * and the verification email — keeping the {@code User} entity inside the service layer (ADR-0004).
 * The controller stays thin and never touches an entity.
 */
@Service
public class RegistrationService {

  private final UserService userService;
  private final EmailVerificationTokenService verificationTokens;
  private final MailService mailService;
  private final ApplicationSettingsService settings;

  public RegistrationService(
      UserService userService,
      EmailVerificationTokenService verificationTokens,
      MailService mailService,
      ApplicationSettingsService settings) {
    this.userService = userService;
    this.verificationTokens = verificationTokens;
    this.mailService = mailService;
    this.settings = settings;
  }

  /**
   * Registers a local user and sends a verification email. Anti-enumeration: if an internal user
   * with the same username/email already exists, this is a silent no-op (no create, no re-mail).
   */
  @Transactional
  public void register(String username, String email, String password, String displayName) {
    if (userService.internalUserExists(username, email)) {
      return;
    }
    User user =
        userService.createSelfRegistered(username, email, password, displayName, defaultRole());
    String rawToken = verificationTokens.issue(user).rawToken();
    sendVerificationEmail(user, rawToken);
  }

  /**
   * The configured global role for self-registered accounts. Defensively falls back to {@link
   * UserRole#MEMBER} for any unknown value and never lets self-registration mint an {@code ADMIN}.
   */
  private UserRole defaultRole() {
    String configured =
        settings.getString(ApplicationSettingKey.AUTH_SELF_REGISTRATION_DEFAULT_ROLE);
    UserRole role;
    try {
      role = UserRole.valueOf(configured);
    } catch (IllegalArgumentException | NullPointerException e) {
      role = UserRole.MEMBER;
    }
    return role == UserRole.ADMIN ? UserRole.MEMBER : role;
  }

  /** Consumes a verification token and activates the account. Throws on an invalid token. */
  @Transactional
  public void verify(String rawToken) {
    User user = verificationTokens.consume(rawToken);
    userService.enable(user.getId());
  }

  private void sendVerificationEmail(User user, String rawToken) {
    String actionUrl =
        baseUrl() + "/verify-email?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    mailService.sendMailFromTemplate(
        MailTemplateKey.REGISTRATION_VERIFICATION,
        user.getEmail(),
        Map.of(
            "siteName", siteName(),
            "recipientName", user.getDisplayName(),
            "actionUrl", actionUrl),
        null);
  }

  private String siteName() {
    return settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME);
  }

  private String baseUrl() {
    String base = settings.getString(ApplicationSettingKey.GENERAL_BASE_URL);
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }
}
