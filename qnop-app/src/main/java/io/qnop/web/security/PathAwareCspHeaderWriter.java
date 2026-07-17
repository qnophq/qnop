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
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;

/**
 * One Content-Security-Policy per surface (ADR-0040). API and actuator responses keep the
 * deny-everything policy a JSON API wants; responses that serve the embedded SPA get a browser-app
 * policy instead: MUI's Emotion styling requires inline styles, pdf.js spawns blob workers, and
 * avatars/thumbnails render from blob/data URLs. Everything still pins to {@code 'self'} — no
 * remote script, style, or connect targets.
 */
final class PathAwareCspHeaderWriter implements HeaderWriter {

  private static final String HEADER = "Content-Security-Policy";

  private static final String API_CSP =
      "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";

  private static final String SPA_CSP =
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
          + "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; "
          + "worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'self'; "
          + "form-action 'self'; object-src 'none'";

  @Override
  public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
    if (response.containsHeader(HEADER)) {
      return;
    }
    String path = request.getRequestURI();
    boolean api =
        path.startsWith("/api/") || path.startsWith("/actuator/") || path.equals("/actuator");
    response.setHeader(HEADER, api ? API_CSP : SPA_CSP);
  }
}
