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

import io.qnop.entity.PasswordResetToken;
import io.qnop.entity.User;
import io.qnop.repository.PasswordResetTokenRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and consumes single-use password-reset tokens (issue #20). Storage mirrors {@link
 * EmailVerificationTokenService} (32 random bytes, only the SHA-256 hex persisted, single-use); the
 * difference is the TTL, which is operator-tunable at runtime via {@code
 * auth.password_reset_token_ttl_minutes} (issue #16) rather than a fixed constant. A daily sweep
 * purges expired rows.
 */
@Service
public class PasswordResetTokenService {

  static final int TOKEN_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final PasswordResetTokenRepository tokens;
  private final ApplicationSettingsService settings;

  public PasswordResetTokenService(
      PasswordResetTokenRepository tokens, ApplicationSettingsService settings) {
    this.tokens = tokens;
    this.settings = settings;
  }

  /** Issues a fresh reset token for {@code user}, superseding any earlier unconsumed one. */
  @Transactional
  public IssuedResetToken issue(User user) {
    Instant now = Instant.now();
    tokens.findUnconsumedTokensForUser(user.getId()).forEach(token -> token.setConsumedAt(now));
    String rawToken = generateRawToken();
    Instant expiresAt = now.plus(ttl());
    tokens.save(new PasswordResetToken(user, sha256Hex(rawToken), expiresAt));
    return new IssuedResetToken(rawToken, expiresAt);
  }

  /** Validates and consumes a reset token, returning its owner. Throws if unknown/expired/used. */
  @Transactional
  public User consume(String rawToken) {
    PasswordResetToken token =
        tokens
            .findByTokenHash(sha256Hex(rawToken))
            .orElseThrow(
                () -> new InvalidPasswordResetTokenException("Unknown or invalid reset token"));
    if (token.getConsumedAt() != null) {
      throw new InvalidPasswordResetTokenException("Reset token already used");
    }
    if (token.getExpiresAt().isBefore(Instant.now())) {
      throw new InvalidPasswordResetTokenException("Reset token has expired");
    }
    token.setConsumedAt(Instant.now());
    return token.getUser();
  }

  @Scheduled(cron = "0 35 3 * * *")
  @SchedulerLock(name = "passwordResetTokenSweep", lockAtMostFor = "PT5M")
  @Transactional
  public void sweep() {
    tokens.deleteByExpiresAtBefore(Instant.now());
  }

  private Duration ttl() {
    return Duration.ofMinutes(
        settings.getInteger(ApplicationSettingKey.AUTH_PASSWORD_RESET_TOKEN_TTL_MINUTES));
  }

  private static String generateRawToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256Hex(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** A freshly issued reset token: the raw value (emailed once) and its absolute expiry. */
  public record IssuedResetToken(String rawToken, Instant expiresAt) {}

  /** Raised when a presented reset token is unknown, expired, or already consumed. */
  public static class InvalidPasswordResetTokenException extends RuntimeException {
    public InvalidPasswordResetTokenException(String message) {
      super(message);
    }
  }
}
