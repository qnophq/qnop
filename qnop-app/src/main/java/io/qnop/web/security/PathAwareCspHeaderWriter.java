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
 * policy instead: pdf.js spawns blob workers, and avatars/thumbnails render from blob/data URLs.
 * Everything still pins to {@code 'self'} — no remote script, style, or connect targets.
 *
 * <p>Styles are nonce-based (issue #913): {@code style-src} allows {@code 'self'} plus this
 * request's {@link CspNonce}, which {@code index.html} hands to Emotion and the few libraries that
 * inject their own {@code <style>} element. There is no {@code 'unsafe-inline'}, so neither an
 * injected {@code <style>} element nor a {@code style="…"} attribute in markup is applied; style
 * writes through the CSSOM (React's {@code style} prop) are not governed by CSP and keep working.
 *
 * <p>The writer always sets the header. A handler that genuinely needs a different policy must say
 * so with {@link #optOut(HttpServletRequest)} and set its own — a header that merely happens to be
 * present is overwritten, so no code path can weaken the policy by accident.
 */
public final class PathAwareCspHeaderWriter implements HeaderWriter {

  private static final String HEADER = "Content-Security-Policy";

  /** Request attribute marking a handler's explicit opt-out; see {@link #optOut}. */
  static final String OPT_OUT_ATTRIBUTE = PathAwareCspHeaderWriter.class.getName() + ".OPT_OUT";

  private static final String API_CSP =
      "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";

  private static final String SPA_CSP =
      "default-src 'self'; script-src 'self'; style-src 'self' 'nonce-%s'; "
          + "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; "
          + "worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'self'; "
          + "form-action 'self'; object-src 'none'";

  PathAwareCspHeaderWriter() {}

  /**
   * Hands the Content-Security-Policy of this response to the calling handler, which then MUST set
   * one itself. The only way to keep this writer from setting its policy.
   */
  public static void optOut(HttpServletRequest request) {
    request.setAttribute(OPT_OUT_ATTRIBUTE, Boolean.TRUE);
  }

  @Override
  public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
    if (Boolean.TRUE.equals(request.getAttribute(OPT_OUT_ATTRIBUTE))) {
      return;
    }
    String path = request.getRequestURI();
    boolean api =
        path.startsWith("/api/") || path.startsWith("/actuator/") || path.equals("/actuator");
    response.setHeader(HEADER, api ? API_CSP : SPA_CSP.formatted(CspNonce.forRequest(request)));
  }
}
