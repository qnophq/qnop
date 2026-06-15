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
import io.qnop.entity.UserSource;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserNotFoundException;
import io.qnop.service.UserService;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Superadmin-initiated password reset for another user (issue #20). Issues a single-use reset token
 * and emails the link (reusing the self-service {@link PasswordResetTokenService} + {@link
 * MailService}). Refuses to reset the actor's own account (they must use change-password) and
 * refuses external (OIDC) users (their credentials live upstream). When SMTP delivery does not
 * succeed, the reset URL is returned in the {@link Result} so the operator can hand it over
 * out-of-band.
 */
@Service
public class AdminPasswordResetService {

  private final UserService userService;
  private final PasswordResetTokenService resetTokens;
  private final MailService mailService;
  private final ApplicationSettingsService settings;

  public AdminPasswordResetService(
      UserService userService,
      PasswordResetTokenService resetTokens,
      MailService mailService,
      ApplicationSettingsService settings) {
    this.userService = userService;
    this.resetTokens = resetTokens;
    this.mailService = mailService;
    this.settings = settings;
  }

  /** Triggers a reset for {@code targetUserId} on behalf of {@code actorUserId}. */
  @Transactional
  public Result trigger(UUID targetUserId, UUID actorUserId) {
    if (targetUserId.equals(actorUserId)) {
      throw new SelfResetNotAllowedException("Use change-password to update your own password.");
    }
    User user =
        userService
            .findById(targetUserId)
            .orElseThrow(() -> new UserNotFoundException(targetUserId));
    if (user.getSource() == UserSource.EXTERNAL) {
      throw new ExternalUserResetNotAllowedException(
          "Cannot reset the password of an external (OIDC) user — credentials live with the"
              + " upstream provider.");
    }

    PasswordResetTokenService.IssuedResetToken issued = resetTokens.issue(user);
    String resetUrl =
        baseUrl()
            + "/reset-password?token="
            + URLEncoder.encode(issued.rawToken(), StandardCharsets.UTF_8);
    MailService.SendResult send =
        mailService.sendMailFromTemplate(
            MailTemplateKey.ADMIN_PASSWORD_RESET,
            user.getEmail(),
            Map.of(
                "siteName", siteName(),
                "recipientName", user.getDisplayName(),
                "actionUrl", resetUrl),
            null);
    boolean emailSent = send instanceof MailService.SendResult.Sent;
    // SMTP-down fallback: surface the link to the operator only when it could not be emailed.
    return new Result(true, emailSent, issued.expiresAt(), emailSent ? null : resetUrl);
  }

  private String siteName() {
    return settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME);
  }

  private String baseUrl() {
    String base = settings.getString(ApplicationSettingKey.GENERAL_BASE_URL);
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  /**
   * Outcome of an admin-triggered reset. {@code resetUrl} is non-null only on the SMTP fallback.
   */
  public record Result(
      boolean tokenIssued, boolean emailSent, Instant expiresAt, String resetUrl) {}

  /** Raised when an admin tries to reset their own password (use change-password instead). */
  public static class SelfResetNotAllowedException extends RuntimeException {
    public SelfResetNotAllowedException(String message) {
      super(message);
    }
  }

  /** Raised when an admin tries to reset an external (OIDC) user's password. */
  public static class ExternalUserResetNotAllowedException extends RuntimeException {
    public ExternalUserResetNotAllowedException(String message) {
      super(message);
    }
  }
}
