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

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** One CSP per surface (ADR-0040): deny-all for the API, a browser-app policy for the SPA. */
class PathAwareCspHeaderWriterTest {

  private static final Pattern STYLE_SRC = Pattern.compile("style-src ([^;]*)");

  private final PathAwareCspHeaderWriter writer = new PathAwareCspHeaderWriter();

  private static MockHttpServletRequest request(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setRequestURI(uri);
    return request;
  }

  private String cspFor(MockHttpServletRequest request) {
    MockHttpServletResponse response = new MockHttpServletResponse();
    writer.writeHeaders(request, response);
    return response.getHeader("Content-Security-Policy");
  }

  private String cspFor(String uri) {
    return cspFor(request(uri));
  }

  private static String styleSrc(String csp) {
    Matcher matcher = STYLE_SRC.matcher(csp);
    assertThat(matcher.find()).as("style-src in %s", csp).isTrue();
    return matcher.group(1);
  }

  @Test
  @DisplayName("API and actuator responses keep the deny-everything policy")
  void apiKeepsDenyAll() {
    assertThat(cspFor("/api/v1/documents")).isEqualTo(cspFor("/actuator/health"));
    assertThat(cspFor("/api/v1/documents")).startsWith("default-src 'none'");
    assertThat(cspFor("/api/v1/documents")).doesNotContain("nonce");
  }

  @Test
  @DisplayName("SPA responses get the browser-app policy, still pinned to 'self'")
  void spaGetsBrowserPolicy() {
    String csp = cspFor("/reviews/some-slug");
    assertThat(csp).startsWith("default-src 'self'");
    assertThat(csp).contains("worker-src 'self' blob:"); // pdf.js workers
    assertThat(csp).contains("frame-ancestors 'none'");
    assertThat(csp).doesNotContain("http"); // no remote origins anywhere
  }

  @Test
  @DisplayName("SPA styles are allowed by the request's nonce, never by 'unsafe-inline'")
  void spaStylesAreNonceBased() {
    MockHttpServletRequest request = request("/");
    String csp = cspFor(request);

    assertThat(csp).doesNotContain("unsafe-inline");
    assertThat(csp).doesNotContain("style-src-attr").doesNotContain("style-src-elem");
    String nonce = CspNonce.forRequest(request);
    assertThat(styleSrc(csp)).isEqualTo("'self' 'nonce-" + nonce + "'");
    assertThat(Base64.getDecoder().decode(nonce)).hasSize(16);
  }

  @Test
  @DisplayName("every request gets its own nonce; within a request it is stable")
  void noncePerRequest() {
    MockHttpServletRequest first = request("/");
    String nonce = CspNonce.forRequest(first);

    assertThat(styleSrc(cspFor(first))).contains("'nonce-" + nonce + "'");
    assertThat(CspNonce.forRequest(first)).isEqualTo(nonce);
    assertThat(styleSrc(cspFor("/"))).doesNotContain(nonce);
  }

  @Test
  @DisplayName("a policy a handler already set is replaced, never kept")
  void existingPolicyIsReplaced() {
    MockHttpServletRequest request = request("/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setHeader("Content-Security-Policy", "default-src *");

    writer.writeHeaders(request, response);

    assertThat(response.getHeader("Content-Security-Policy")).startsWith("default-src 'self'");
  }
}
