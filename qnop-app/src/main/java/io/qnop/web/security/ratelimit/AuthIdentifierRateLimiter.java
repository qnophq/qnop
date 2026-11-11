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
package io.qnop.web.security.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Per-identifier auth throttles (issue #899, ADR-0027): the buckets that sit next to the per-IP
 * filters and are keyed on what the request is <em>about</em> — the account being signed in to, the
 * e-mail address being registered or sent a reset link, the reset token being redeemed. Per-IP
 * limits alone are defeated by a caller that spreads its attempts over many source addresses.
 *
 * <p>These need the parsed request body, so the controllers call them rather than a servlet filter
 * (which would have to buffer and re-parse the body). A rejected check throws {@link
 * RateLimitExceededException}, rendered by {@link RateLimitExceptionHandler} as the same {@code
 * 429} + {@code Retry-After} body the per-IP filter of that endpoint writes.
 *
 * <p><b>No enumeration.</b> Buckets are keyed on a SHA-256 of the normalized identifier, are
 * created for any identifier whether or not an account exists behind it, and are consulted before
 * the account is looked up. A throttled response is therefore the same for a known and an unknown
 * identifier. The raw identifier is never held in the bucket store.
 */
@Component
public class AuthIdentifierRateLimiter {

  static final String LOGIN_ACCOUNT = "login-account";
  static final String REGISTER_EMAIL = "register-email";
  static final String FORGOT_PASSWORD_EMAIL = "forgot-password-email";
  static final String RESET_PASSWORD_TOKEN = "reset-password-token";

  private final BucketRateLimitService rateLimitService;
  private final RateLimitProperties properties;

  public AuthIdentifierRateLimiter(
      BucketRateLimitService rateLimitService, RateLimitProperties properties) {
    this.rateLimitService = rateLimitService;
    this.properties = properties;
  }

  /**
   * A login attempt for {@code usernameOrEmail}: takes a token from the account's bucket, so a
   * drained bucket refuses the attempt before any credential check (a correct password included).
   * Pair with {@link #loginSucceeded} so only failed attempts count.
   */
  public void beginLogin(String usernameOrEmail) {
    consume(
        LOGIN_ACCOUNT,
        normalizedHash(usernameOrEmail),
        properties.loginAccount(),
        LoginRateLimitFilter.MESSAGE);
  }

  /** Returns the token {@link #beginLogin} took: a successful sign-in is not a failed attempt. */
  public void loginSucceeded(String usernameOrEmail) {
    rateLimitService.refund(LOGIN_ACCOUNT, normalizedHash(usernameOrEmail));
  }

  /** A registration for {@code email}. */
  public void register(String email) {
    consume(
        REGISTER_EMAIL,
        normalizedHash(email),
        properties.registerEmail(),
        RegisterRateLimitFilter.MESSAGE);
  }

  /** A password-reset request for {@code email}. */
  public void forgotPassword(String email) {
    consume(
        FORGOT_PASSWORD_EMAIL,
        normalizedHash(email),
        properties.forgotPasswordEmail(),
        ForgotPasswordRateLimitFilter.MESSAGE);
  }

  /** A redemption attempt of {@code token}. The token is case-sensitive, so it is not folded. */
  public void resetPassword(String token) {
    consume(
        RESET_PASSWORD_TOKEN,
        sha256(token == null ? "" : token),
        properties.resetPasswordToken(),
        ResetPasswordRateLimitFilter.MESSAGE);
  }

  private void consume(String scope, String key, RateLimitProperties.Limit limit, String message) {
    RateLimitResult result =
        rateLimitService.tryConsume(scope, key, limit.maxAttempts(), limit.windowSeconds());
    if (result instanceof RateLimitResult.Rejected rejected) {
      throw new RateLimitExceededException(message, rejected.retryAfterSeconds());
    }
  }

  /** Trim + lower-case, so trivially different spellings of one identifier share a bucket. */
  static String normalizedHash(String identifier) {
    return sha256(identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT));
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform is required to provide SHA-256.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
