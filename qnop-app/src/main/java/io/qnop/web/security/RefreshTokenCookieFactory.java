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
package io.qnop.web.security;

import io.qnop.security.QnopProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code Set-Cookie} header carrying the opaque refresh token (issue #17). {@code
 * HttpOnly} keeps it out of JavaScript (XSS cannot exfiltrate the session), {@code SameSite=Strict}
 * plus the narrow {@code /api/v1/auth} path defend against CSRF, and {@code Secure} is on by
 * default (toggle off via {@code qnop.auth.cookie-secure} only for local HTTP dev).
 */
@Component
public class RefreshTokenCookieFactory {

  /** Cookie name — stable so refactors cannot silently rename it. */
  public static final String COOKIE_NAME = "qnop_refresh";

  /** Narrow path: the cookie is sent only to the auth endpoints. */
  public static final String COOKIE_PATH = "/api/v1/auth";

  private static final String SAME_SITE = "Strict";

  private final boolean secure;

  public RefreshTokenCookieFactory(QnopProperties properties) {
    this.secure = Boolean.TRUE.equals(properties.auth().cookieSecure());
  }

  /** Cookie for a freshly issued refresh token. */
  public ResponseCookie build(String plaintext, Duration maxAge) {
    return base(plaintext).maxAge(maxAge).build();
  }

  /** Clearing cookie (empty value, immediate expiry) for logout / failed refresh. */
  public ResponseCookie clear() {
    return base("").maxAge(0).build();
  }

  private ResponseCookie.ResponseCookieBuilder base(String value) {
    return ResponseCookie.from(COOKIE_NAME, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite(SAME_SITE)
        .path(COOKIE_PATH);
  }
}
