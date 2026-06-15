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

import io.qnop.entity.EmailVerificationToken;
import io.qnop.entity.User;
import io.qnop.repository.EmailVerificationTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and consumes single-use email-verification tokens (issue #20). A token is 32 random bytes,
 * surfaced once to the user (in the verification email) and stored only as its SHA-256 hex digest
 * (issue #12), so a DB leak cannot reconstruct a usable link. Tokens live 24h and are single-use;
 * issuing a fresh token supersedes any earlier unconsumed one for the same user. A daily scheduled
 * sweep purges expired rows.
 */
@Service
public class EmailVerificationTokenService {

  static final int TOKEN_BYTES = 32;
  static final Duration TOKEN_TTL = Duration.ofHours(24);

  private static final SecureRandom RANDOM = new SecureRandom();

  private final EmailVerificationTokenRepository tokens;

  public EmailVerificationTokenService(EmailVerificationTokenRepository tokens) {
    this.tokens = tokens;
  }

  /** Issues a fresh token for {@code user}, superseding any earlier unconsumed one. */
  @Transactional
  public IssuedToken issue(User user) {
    Instant now = Instant.now();
    tokens.findUnconsumedTokensForUser(user.getId()).forEach(token -> token.setConsumedAt(now));
    String rawToken = generateRawToken();
    Instant expiresAt = now.plus(TOKEN_TTL);
    tokens.save(new EmailVerificationToken(user, sha256Hex(rawToken), expiresAt));
    return new IssuedToken(rawToken, expiresAt);
  }

  /** Validates and consumes a token, returning its owner. Throws if unknown/expired/used. */
  @Transactional
  public User consume(String rawToken) {
    EmailVerificationToken token =
        tokens
            .findByTokenHash(sha256Hex(rawToken))
            .orElseThrow(
                () ->
                    new InvalidVerificationTokenException("Unknown or invalid verification token"));
    if (token.getConsumedAt() != null) {
      throw new InvalidVerificationTokenException("Verification token already used");
    }
    if (token.getExpiresAt().isBefore(Instant.now())) {
      throw new InvalidVerificationTokenException("Verification token has expired");
    }
    token.setConsumedAt(Instant.now());
    return token.getUser();
  }

  /** Daily off-peak purge of expired rows so the table does not grow unbounded. */
  @Scheduled(cron = "0 30 3 * * *")
  @Transactional
  public void sweep() {
    tokens.deleteByExpiresAtBefore(Instant.now());
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

  /** A freshly issued token: the raw value (emailed once) and its absolute expiry. */
  public record IssuedToken(String rawToken, Instant expiresAt) {}

  /** Raised when a presented verification token is unknown, expired, or already consumed. */
  public static class InvalidVerificationTokenException extends RuntimeException {
    public InvalidVerificationTokenException(String message) {
      super(message);
    }
  }
}
