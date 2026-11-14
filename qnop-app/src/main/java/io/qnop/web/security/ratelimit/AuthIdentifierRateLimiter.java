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

import io.qnop.observability.metrics.AuthMeters;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-identifier auth throttles (issue #899, ADR-0027): the buckets that sit next to the per-IP
 * filters and are keyed on what the request is <em>about</em> — the account being signed in to, the
 * e-mail address being sent a mail, the reset token being redeemed. Per-IP limits alone are
 * defeated by a caller that spreads its attempts over many source addresses.
 *
 * <p>These need the parsed request body, so the controllers call them rather than a servlet filter.
 * A {@code 429} is thrown as {@link RateLimitExceededException} and rendered by {@link
 * RateLimitExceptionHandler} exactly like the endpoint's per-IP filter renders its own.
 *
 * <p><b>Only the caller's own bucket answers 429.</b> Each throttle has a strict bucket per
 * identifier and <em>source</em> — the client network (IPv4 /24, IPv6 /56) or, for login, a
 * recognised device ({@link LoginDeviceCookies}) — and a much larger ceiling per identifier across
 * all sources. Only the source bucket refuses with {@code 429}. A drained ceiling refuses
 * <em>silently</em>: a login gets the ordinary {@code 401} without its password being honoured, a
 * reset request the ordinary {@code 204} without a mail. So what one source does never shows up as
 * a {@code 429} elsewhere, and a ceiling that is held drained neither reveals itself nor which
 * identifiers share it.
 *
 * <p><b>Login.</b> Only failures count: tokens are taken before the credential check (atomic, so
 * concurrent attempts cannot overshoot) and given back on success. An attempt from a recognised
 * device is charged to that device's bucket and is exempt from the ceiling, so the owner keeps
 * access from devices they have signed in on whatever happens elsewhere; every other attempt is
 * charged to its network bucket and the ceiling. The account key is the user id when the identifier
 * resolves to a local account — handed in by the credential check's own lookup ({@code
 * AuthService.authenticate} with a gate), so both agree on which account is meant; an identifier
 * that resolves to nothing is keyed on its hash, folded the way that lookup folds.
 *
 * <p><b>Register / reset token</b> keep a single hard cap per e-mail / token. The buckets live in a
 * store of their own, separate from the per-IP filters', so neither can evict the other's. Keys are
 * SHA-256 hashes or user ids; no raw identifier is held.
 */
@Component
public class AuthIdentifierRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(AuthIdentifierRateLimiter.class);

  static final String LOGIN_ACCOUNT = "login-account";
  static final String LOGIN_ACCOUNT_NETWORK = "login-account-network";
  static final String REGISTER_EMAIL = "register-email";
  static final String FORGOT_PASSWORD_EMAIL = "forgot-password-email";
  static final String FORGOT_PASSWORD_EMAIL_NETWORK = "forgot-password-email-network";
  static final String RESET_PASSWORD_TOKEN = "reset-password-token";

  private final BucketRateLimitService buckets;
  private final RateLimitProperties properties;
  private final HttpClientIpResolver clientIpResolver;
  private final LoginDeviceCookies deviceCookies;

  /** Production wiring: a bucket store of its own, sized by {@code max-buckets}. */
  @Autowired
  public AuthIdentifierRateLimiter(
      RateLimitProperties properties,
      HttpClientIpResolver clientIpResolver,
      LoginDeviceCookies deviceCookies,
      AuthMeters meters) {
    this(
        new BucketRateLimitService(properties, meters),
        properties,
        clientIpResolver,
        deviceCookies);
  }

  AuthIdentifierRateLimiter(
      BucketRateLimitService buckets,
      RateLimitProperties properties,
      HttpClientIpResolver clientIpResolver,
      LoginDeviceCookies deviceCookies) {
    this.buckets = buckets;
    this.properties = properties;
    this.clientIpResolver = clientIpResolver;
    this.deviceCookies = deviceCookies;
  }

  /**
   * What {@link #beginLogin} charged. {@code refused} means the account's ceiling is drained and
   * the attempt must fail like a wrong password, whatever the credentials. Hand it to {@link
   * #loginSucceeded} when the credentials were right.
   */
  public record LoginAttempt(String sourceKey, String ceilingKey, boolean refused) {}

  /**
   * A login attempt for {@code usernameOrEmail}, which resolves to the local account {@code
   * accountId} (empty when none), from {@code request}'s client. Throws {@link
   * RateLimitExceededException} when the caller's own source bucket for the account is drained.
   */
  public LoginAttempt beginLogin(
      String usernameOrEmail, Optional<UUID> accountId, HttpServletRequest request) {
    String accountKey =
        accountId
            .map(id -> "user:" + id)
            .orElseGet(() -> "id:" + loginIdentifierHash(usernameOrEmail));
    Optional<String> device = accountId.flatMap(id -> deviceCookies.recognisedDevice(request, id));
    if (device.isPresent()) {
      String deviceKey = accountKey + "|device:" + device.get();
      consume(LOGIN_ACCOUNT_NETWORK, deviceKey, properties.loginAccountNetwork(), loginMessage());
      return new LoginAttempt(deviceKey, null, false);
    }
    String networkKey =
        accountKey + "|net:" + ClientNetworks.networkKey(clientIpResolver.resolve(request));
    consume(LOGIN_ACCOUNT_NETWORK, networkKey, properties.loginAccountNetwork(), loginMessage());
    if (!tryConsume(LOGIN_ACCOUNT, accountKey, properties.loginAccount())) {
      // Silent refusal, and the network token stays spent. Reaching the ceiling takes failures
      // from many networks: worth an operator's eye. The key is a user id or a hash, never the
      // identifier that was typed (issue #659).
      log.warn("Login ceiling reached for account {}", accountKey);
      return new LoginAttempt(networkKey, null, true);
    }
    return new LoginAttempt(networkKey, accountKey, false);
  }

  /** Returns the tokens {@link #beginLogin} took: a successful sign-in is not a failed attempt. */
  public void loginSucceeded(LoginAttempt attempt) {
    buckets.refund(LOGIN_ACCOUNT_NETWORK, attempt.sourceKey());
    if (attempt.ceilingKey() != null) {
      buckets.refund(LOGIN_ACCOUNT, attempt.ceilingKey());
    }
  }

  /**
   * Folds an unresolved login identifier the way the account lookup would: e-mail addresses match
   * case-insensitively, usernames exactly, and neither is trimmed.
   */
  static String loginIdentifierHash(String usernameOrEmail) {
    String value = usernameOrEmail == null ? "" : usernameOrEmail;
    return sha256(value.indexOf('@') >= 0 ? value.toLowerCase(Locale.ROOT) : value);
  }

  /** A registration for {@code email}: one hard cap per address. */
  public void register(String email) {
    consume(
        REGISTER_EMAIL,
        normalizedHash(email),
        properties.registerEmail(),
        RegisterRateLimitFilter.MESSAGE);
  }

  /**
   * A password-reset request for {@code email} from {@code request}'s client. Throws {@link
   * RateLimitExceededException} when this network's bucket for the address is drained; returns
   * {@code false} — answer as usual, send nothing — when the address's overall ceiling is.
   */
  public boolean mayRequestPasswordReset(String email, HttpServletRequest request) {
    String emailKey = normalizedHash(email);
    String networkKey =
        emailKey + "|net:" + ClientNetworks.networkKey(clientIpResolver.resolve(request));
    consume(
        FORGOT_PASSWORD_EMAIL_NETWORK,
        networkKey,
        properties.forgotPasswordEmailNetwork(),
        ForgotPasswordRateLimitFilter.MESSAGE);
    if (!tryConsume(FORGOT_PASSWORD_EMAIL, emailKey, properties.forgotPasswordEmail())) {
      log.info("Password-reset mail suppressed: ceiling reached for one address");
      return false;
    }
    return true;
  }

  /**
   * A redemption attempt of {@code token}. Bounds retries with one (possibly leaked) token; it does
   * nothing against guessing, where each guess is a new token — that is the per-IP filter's job.
   * The token is case-sensitive, so it is not folded.
   */
  public void resetPassword(String token) {
    consume(
        RESET_PASSWORD_TOKEN,
        sha256(token == null ? "" : token),
        properties.resetPasswordToken(),
        ResetPasswordRateLimitFilter.MESSAGE);
  }

  private static String loginMessage() {
    return LoginRateLimitFilter.MESSAGE;
  }

  private boolean tryConsume(String scope, String key, RateLimitProperties.Limit limit) {
    return buckets.tryConsume(scope, key, limit.maxAttempts(), limit.windowSeconds())
        instanceof RateLimitResult.Allowed;
  }

  private void consume(String scope, String key, RateLimitProperties.Limit limit, String message) {
    RateLimitResult result =
        buckets.tryConsume(scope, key, limit.maxAttempts(), limit.windowSeconds());
    if (result instanceof RateLimitResult.Rejected rejected) {
      throw new RateLimitExceededException(message, rejected.retryAfterSeconds());
    }
  }

  /** Trim + lower-case, so trivially different spellings of one address share a bucket. */
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
