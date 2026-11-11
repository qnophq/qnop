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
package io.qnop.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The embedded SPA's {@code index.html} carries a fresh CSP nonce per request, the same one the
 * response's {@code style-src} allows, and the policy has no {@code 'unsafe-inline'} left (issue
 * #913). The test classpath ships a stand-in bundle under {@code static/}. Requires Docker
 * (Testcontainers).
 */
class SpaCspNonceIT extends AbstractIntegrationTest {

  private static final Pattern STYLE_SRC = Pattern.compile("style-src 'self' 'nonce-([^']+)'");

  private static final Pattern META_NONCE =
      Pattern.compile("<meta property=\"csp-nonce\" nonce=\"([^\"]+)\"");

  @LocalServerPort int port;

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
  }

  private static String csp(HttpResponse<String> response) {
    return response.headers().firstValue("Content-Security-Policy").orElseThrow();
  }

  private static String group(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).as("%s in %s", pattern, text).isTrue();
    return matcher.group(1);
  }

  @Test
  @DisplayName("index.html and the policy header carry the same nonce, fresh per request")
  void indexCarriesTheHeaderNonce() throws Exception {
    HttpResponse<String> first = get("/");
    HttpResponse<String> second = get("/reviews/some-slug");

    for (HttpResponse<String> response : java.util.List.of(first, second)) {
      assertThat(response.statusCode()).isEqualTo(200);
      String headerNonce = group(STYLE_SRC, csp(response));
      assertThat(group(META_NONCE, response.body())).isEqualTo(headerNonce);
      assertThat(response.body()).doesNotContain("__CSP_NONCE__");
      assertThat(response.body()).contains("nonce=\"" + headerNonce + "\"></script>");
    }
    assertThat(group(STYLE_SRC, csp(first))).isNotEqualTo(group(STYLE_SRC, csp(second)));
  }

  @Test
  @DisplayName("no 'unsafe-inline' remains in the SPA policy")
  void noUnsafeInline() throws Exception {
    assertThat(csp(get("/"))).doesNotContain("unsafe-inline");
    assertThat(csp(get("/index.html"))).doesNotContain("unsafe-inline");
  }

  @Test
  @DisplayName("index.html is never stored; hashed assets are cached as immutable")
  void cachingSplitsTheHtmlFromHashedAssets() throws Exception {
    HttpResponse<String> index = get("/index.html");
    assertThat(index.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(index.headers().firstValue("Last-Modified")).isEmpty();
    assertThat(index.headers().firstValue("ETag")).isEmpty();

    HttpResponse<String> asset = get("/assets/index-test.js");
    assertThat(asset.statusCode()).isEqualTo(200);
    assertThat(asset.headers().firstValue("Cache-Control").orElseThrow())
        .contains("immutable")
        .contains("max-age=31536000");
  }

  @Test
  @DisplayName("a missing hashed asset or tracking path is a 404, not the SPA page")
  void noFallbackOutsideClientRoutes() throws Exception {
    assertThat(get("/assets/missing-chunk.js").statusCode()).isEqualTo(404);
    assertThat(get("/t/s.js").body()).doesNotContain("csp-nonce");
  }

  @Test
  @DisplayName("unknown API paths stay 404s and keep the deny-all policy")
  void apiIsUnaffected() throws Exception {
    HttpResponse<String> response = get("/api/v1/no-such-route");
    assertThat(response.body()).doesNotContain("csp-nonce");
    assertThat(csp(response)).startsWith("default-src 'none'");
  }
}
