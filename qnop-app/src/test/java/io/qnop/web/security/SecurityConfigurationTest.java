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

import io.qnop.bootstrap.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Verifies the security filter chain (ADR-0022): the context starts with the chain, actuator health
 * is public, unauthenticated requests to protected paths get a 401, and the security headers are
 * present. Drives the live server over HTTP (like {@code QnopApplicationContextTest}) to stay free
 * of a servlet test-client dependency. Requires Docker (Testcontainers).
 */
class SecurityConfigurationTest extends AbstractIntegrationTest {

  @LocalServerPort int port;

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
  }

  @Test
  void healthEndpointIsPublic() throws Exception {
    // "Public" means the security chain does not challenge the request — it must not be
    // 401/403. The health status itself (UP, 200) is asserted by QnopApplicationContextTest;
    // asserting it here too would couple this test to transient health aggregation (e.g. a
    // brief 503 during readiness/shutdown).
    assertThat(get("/actuator/health").statusCode()).isNotIn(401, 403);
  }

  @Test
  void unauthenticatedRequestToProtectedPathReturns401() throws Exception {
    assertThat(get("/api/anything").statusCode()).isEqualTo(401);
  }

  @Test
  void securityHeadersArePresent() throws Exception {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
    assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
    assertThat(response.headers().firstValue("Content-Security-Policy")).isPresent();
    assertThat(response.headers().firstValue("Referrer-Policy"))
        .hasValue("strict-origin-when-cross-origin");
  }
}
