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

import io.qnop.security.JwtKeyService;
import io.qnop.security.QnopProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Recognised-device cookies for the login throttle (issue #899, ADR-0027; OWASP "device cookie"
 * pattern). A successful sign-in leaves a signed cookie bound to the account; a later attempt for
 * the same account that presents a valid one comes from a device that has proven the password
 * before. Such attempts are charged to a bucket of their own device and are exempt from the
 * account-wide ceiling, so failures from elsewhere can never keep the owner out of a device they
 * already use.
 *
 * <p>The value is {@code base64url(accountId ‖ nonce ‖ issuedAt) . base64url(HMAC-SHA256)} with a
 * key derived from the JWT secret under its own HKDF purpose label (ADR-0022). It carries no
 * personal data beyond the account id it is bound to. {@code HttpOnly}, {@code SameSite=Strict},
 * {@code Secure} (per {@code qnop.auth.cookie-secure}), and sent to the login endpoint only. Its
 * lifetime is bounded by {@code qnop.auth.rate-limit.login-device-cookie-lifetime}; rotating the
 * JWT secret invalidates every device cookie.
 */
@Component
public class LoginDeviceCookies {

  /** Cookie name — stable so refactors cannot silently rename it. */
  public static final String COOKIE_NAME = "qnop_device";

  /** Sent to the login endpoint and nowhere else. */
  public static final String COOKIE_PATH = LoginRateLimitFilter.PATH;

  static final String KEY_PURPOSE = "login-device-cookie";

  private static final int PAYLOAD_BYTES = 16 + 16 + 8;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final SecretKey key;
  private final Duration lifetime;
  private final boolean secure;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  @Autowired
  public LoginDeviceCookies(
      JwtKeyService keys, RateLimitProperties rateLimits, QnopProperties properties) {
    this(keys, rateLimits, properties, Clock.systemUTC());
  }

  LoginDeviceCookies(
      JwtKeyService keys, RateLimitProperties rateLimits, QnopProperties properties, Clock clock) {
    this.key = keys.deriveKey(KEY_PURPOSE);
    this.lifetime = rateLimits.loginDeviceCookieLifetime();
    this.secure = Boolean.TRUE.equals(properties.auth().cookieSecure());
    this.clock = clock;
  }

  /** A fresh cookie for {@code accountId}, set after a successful login. */
  public ResponseCookie issue(UUID accountId) {
    byte[] nonce = new byte[16];
    random.nextBytes(nonce);
    ByteBuffer payload =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .putLong(accountId.getMostSignificantBits())
            .putLong(accountId.getLeastSignificantBits())
            .put(nonce)
            .putLong(clock.instant().getEpochSecond());
    String value =
        ENCODER.encodeToString(payload.array())
            + "."
            + ENCODER.encodeToString(mac(payload.array()));
    return ResponseCookie.from(COOKIE_NAME, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Strict")
        .path(COOKIE_PATH)
        .maxAge(lifetime)
        .build();
  }

  /**
   * The device id (the cookie's random nonce) when {@code request} carries a valid, unexpired
   * device cookie bound to {@code accountId}; empty otherwise.
   */
  public Optional<String> recognisedDevice(HttpServletRequest request, UUID accountId) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null || accountId == null) {
      return Optional.empty();
    }
    for (Cookie cookie : cookies) {
      if (COOKIE_NAME.equals(cookie.getName())) {
        Optional<String> device = verify(cookie.getValue(), accountId);
        if (device.isPresent()) {
          return device;
        }
      }
    }
    return Optional.empty();
  }

  Optional<String> verify(String value, UUID accountId) {
    if (value == null) {
      return Optional.empty();
    }
    int dot = value.indexOf('.');
    if (dot <= 0) {
      return Optional.empty();
    }
    byte[] payload;
    byte[] signature;
    try {
      payload = DECODER.decode(value.substring(0, dot));
      signature = DECODER.decode(value.substring(dot + 1));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (payload.length != PAYLOAD_BYTES || !MessageDigest.isEqual(mac(payload), signature)) {
      return Optional.empty();
    }
    ByteBuffer buffer = ByteBuffer.wrap(payload);
    UUID boundTo = new UUID(buffer.getLong(), buffer.getLong());
    byte[] nonce = new byte[16];
    buffer.get(nonce);
    Instant issuedAt = Instant.ofEpochSecond(buffer.getLong());
    Instant now = clock.instant();
    if (!boundTo.equals(accountId)
        || issuedAt.isAfter(now)
        || !issuedAt.plus(lifetime).isAfter(now)) {
      return Optional.empty();
    }
    return Optional.of(HexFormat.of().formatHex(nonce));
  }

  private byte[] mac(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(payload);
    } catch (GeneralSecurityException e) {
      // HmacSHA256 is mandatory on every Java platform; the key is a fixed 256-bit HMAC key.
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
