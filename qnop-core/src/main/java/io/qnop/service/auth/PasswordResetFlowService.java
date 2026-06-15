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
 * Orchestrates the self-service password-reset flow (issue #20), keeping the {@code User} entity
 * inside the service layer (ADR-0004). {@link #requestReset} is anti-enumeration: it runs only when
 * the feature is enabled and the email maps to an enabled local account, and the caller returns a
 * uniform 204 regardless.
 */
@Service
public class PasswordResetFlowService {

  private final UserService userService;
  private final PasswordResetTokenService resetTokens;
  private final MailService mailService;
  private final ApplicationSettingsService settings;

  public PasswordResetFlowService(
      UserService userService,
      PasswordResetTokenService resetTokens,
      MailService mailService,
      ApplicationSettingsService settings) {
    this.userService = userService;
    this.resetTokens = resetTokens;
    this.mailService = mailService;
    this.settings = settings;
  }

  /**
   * Issues a reset token and emails the link, only for an enabled local account. Silent otherwise.
   */
  @Transactional
  public void requestReset(String email) {
    if (!settings.getBoolean(ApplicationSettingKey.AUTH_PASSWORD_RESET_ENABLED)) {
      return;
    }
    userService.findInternalByEmail(email).filter(User::isEnabled).ifPresent(this::sendResetEmail);
  }

  /** Consumes a reset token and applies the new password. Throws on an invalid token. */
  @Transactional
  public void reset(String rawToken, String newPassword) {
    User user = resetTokens.consume(rawToken);
    userService.applyPasswordReset(user.getId(), newPassword);
  }

  private void sendResetEmail(User user) {
    String rawToken = resetTokens.issue(user).rawToken();
    String actionUrl =
        baseUrl() + "/reset-password?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    mailService.sendMailFromTemplate(
        MailTemplateKey.PASSWORD_RESET,
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
