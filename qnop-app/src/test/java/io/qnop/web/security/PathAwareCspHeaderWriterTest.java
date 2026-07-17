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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** One CSP per surface (ADR-0040): deny-all for the API, a browser-app policy for the SPA. */
class PathAwareCspHeaderWriterTest {

  private final PathAwareCspHeaderWriter writer = new PathAwareCspHeaderWriter();

  private String cspFor(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setRequestURI(uri);
    MockHttpServletResponse response = new MockHttpServletResponse();
    writer.writeHeaders(request, response);
    return response.getHeader("Content-Security-Policy");
  }

  @Test
  @DisplayName("API and actuator responses keep the deny-everything policy")
  void apiKeepsDenyAll() {
    assertThat(cspFor("/api/v1/documents")).isEqualTo(cspFor("/actuator/health"));
    assertThat(cspFor("/api/v1/documents")).startsWith("default-src 'none'");
  }

  @Test
  @DisplayName("SPA responses get the browser-app policy, still pinned to 'self'")
  void spaGetsBrowserPolicy() {
    String csp = cspFor("/reviews/some-slug");
    assertThat(csp).startsWith("default-src 'self'");
    assertThat(csp).contains("worker-src 'self' blob:"); // pdf.js workers
    assertThat(csp).contains("frame-ancestors 'none'");
    assertThat(csp).doesNotContain("http"); // no remote origins anywhere
    assertThat(cspFor("/")).isEqualTo(csp);
  }

  @Test
  @DisplayName("an already-set CSP header is never overwritten")
  void respectsExistingHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.setRequestURI("/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setHeader("Content-Security-Policy", "sentinel");
    writer.writeHeaders(request, response);
    assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("sentinel");
  }
}
