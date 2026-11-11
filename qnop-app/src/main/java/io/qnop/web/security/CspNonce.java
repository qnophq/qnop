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

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The per-request Content-Security-Policy nonce for the embedded SPA (issue #913, ADR-0040
 * amendment). The policy header ({@link PathAwareCspHeaderWriter}) and the {@code index.html} body
 * that hands the nonce to the client must carry the same value, and neither knows which of them
 * runs first, so both ask this class: the first caller in a request mints the nonce, every later
 * caller in the same request gets the same one back.
 *
 * <p>128 bits from {@link SecureRandom}, standard Base64 — the shape CSP Level 3 expects of a nonce
 * source. A nonce is never reused across requests.
 */
public final class CspNonce {

  /** Request attribute holding the nonce once minted. */
  static final String REQUEST_ATTRIBUTE = CspNonce.class.getName();

  private static final int NONCE_BYTES = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  private CspNonce() {}

  /** The nonce of this request, minted on first use. */
  public static String forRequest(HttpServletRequest request) {
    if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof String existing) {
      return existing;
    }
    byte[] bytes = new byte[NONCE_BYTES];
    RANDOM.nextBytes(bytes);
    String nonce = Base64.getEncoder().encodeToString(bytes);
    request.setAttribute(REQUEST_ATTRIBUTE, nonce);
    return nonce;
  }
}
