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

import io.qnop.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>Login: failures only, per network first.</b> A login is charged to two buckets of the
 * account: a strict one per client network (IPv4 /24, IPv6 /64) and a much more generous one across
 * all networks. A token is taken from both before the credential check (atomic, so concurrent
 * attempts cannot overshoot) and both are given back when the login succeeds, so only failures
 * count. A source that keeps failing exhausts its own network bucket and is refused, while the
 * account owner signing in from elsewhere is not; only the overall ceiling, which takes failures
 * from many networks to reach, refuses everyone, and it bounds the total guesses on one account.
 * While a bucket is drained the correct password is refused too; otherwise the refusal would tell
 * which guess was right.
 *
 * <p><b>Which account.</b> The account key is the user id when the identifier resolves to a local
 * account, through {@link AuthService#findLocalAccountId} (the same lookup the credential check
 * uses, run on every attempt), so username and e-mail of one account, and any case variant the
 * e-mail lookup accepts, share one bucket. An identifier that resolves to nothing is keyed on its
 * hash, folded the way the lookup folds: case-insensitive when it looks like an e-mail address
 * (contains {@code @}), exact otherwise. Unknown identifiers get buckets just like known ones, so a
 * refusal does not tell the two apart.
 *
 * <p><b>Register / forgot-password / reset</b> are hard per-e-mail (per-token) caps: they protect a
 * mailbox, not an account, and live in scopes of their own, so draining them never affects the
 * owner's login. Keys are SHA-256 hashes; no raw identifier is held in the bucket store.
 */
@Component
public class AuthIdentifierRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(AuthIdentifierRateLimiter.class);

  static final String LOGIN_ACCOUNT = "login-account";
  static final String LOGIN_ACCOUNT_NETWORK = "login-account-network";
  static final String REGISTER_EMAIL = "register-email";
  static final String FORGOT_PASSWORD_EMAIL = "forgot-password-email";
  static final String RESET_PASSWORD_TOKEN = "reset-password-token";

  private final BucketRateLimitService rateLimitService;
  private final RateLimitProperties properties;
  private final HttpClientIpResolver clientIpResolver;
  private final AuthService authService;

  public AuthIdentifierRateLimiter(
      BucketRateLimitService rateLimitService,
      RateLimitProperties properties,
      HttpClientIpResolver clientIpResolver,
      AuthService authService) {
    this.rateLimitService = rateLimitService;
    this.properties = properties;
    this.clientIpResolver = clientIpResolver;
    this.authService = authService;
  }

  /** The buckets one login attempt was charged to; hand it back to {@link #loginSucceeded}. */
  public record LoginAttempt(String accountKey, String networkKey) {}

  /**
   * A login attempt for {@code usernameOrEmail} from {@code request}'s client: charges the
   * account's per-network and overall buckets, refusing the attempt before any credential check
   * when either is drained. Pair with {@link #loginSucceeded} so only failed attempts count.
   */
  public LoginAttempt beginLogin(String usernameOrEmail, HttpServletRequest request) {
    String accountKey = accountKey(usernameOrEmail);
    String networkKey = accountKey + "|" + networkPrefix(clientIpResolver.resolve(request));
    consume(
        LOGIN_ACCOUNT_NETWORK,
        networkKey,
        properties.loginAccountNetwork(),
        LoginRateLimitFilter.MESSAGE);
    try {
      consume(LOGIN_ACCOUNT, accountKey, properties.loginAccount(), LoginRateLimitFilter.MESSAGE);
    } catch (RateLimitExceededException e) {
      // Refused anyway: do not also count it against this network.
      rateLimitService.refund(LOGIN_ACCOUNT_NETWORK, networkKey);
      // Reaching the overall ceiling takes failures from many networks: worth an operator's eye.
      // The key is a user id or a hash, never the identifier that was typed (issue #659).
      log.warn("Login ceiling reached for account {}", accountKey);
      throw e;
    }
    return new LoginAttempt(accountKey, networkKey);
  }

  /** Returns the tokens {@link #beginLogin} took: a successful sign-in is not a failed attempt. */
  public void loginSucceeded(LoginAttempt attempt) {
    rateLimitService.refund(LOGIN_ACCOUNT_NETWORK, attempt.networkKey());
    rateLimitService.refund(LOGIN_ACCOUNT, attempt.accountKey());
  }

  private String accountKey(String usernameOrEmail) {
    return authService
        .findLocalAccountId(usernameOrEmail)
        .map(id -> "user:" + id)
        .orElseGet(() -> "id:" + loginIdentifierHash(usernameOrEmail));
  }

  /**
   * Folds an unresolved login identifier the way the account lookup would: e-mail addresses match
   * case-insensitively, usernames exactly, and neither is trimmed.
   */
  static String loginIdentifierHash(String usernameOrEmail) {
    String value = usernameOrEmail == null ? "" : usernameOrEmail;
    return sha256(value.indexOf('@') >= 0 ? value.toLowerCase(Locale.ROOT) : value);
  }

  /**
   * The client network an address belongs to (its IPv4 /24 or IPv6 /64), so one source cannot dodge
   * the per-network bucket by moving within the block it controls.
   */
  static String networkPrefix(String clientIp) {
    String canonical =
        HttpClientIpResolver.parseLiteral(clientIp == null ? "" : clientIp).orElse(null);
    if (canonical == null) {
      return "raw:" + clientIp;
    }
    try {
      byte[] bytes = InetAddress.getByName(canonical).getAddress(); // a literal: no lookup
      int keep = bytes.length == 4 ? 3 : 8;
      return HexFormat.of().formatHex(bytes, 0, keep) + "/" + keep * 8;
    } catch (UnknownHostException e) {
      return "raw:" + clientIp;
    }
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
