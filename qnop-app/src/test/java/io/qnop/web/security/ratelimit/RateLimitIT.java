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

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.bootstrap.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end check of the login rate-limit filter (issue #18, ADR-0027) against the live server: a
 * burst of failed logins from one client is throttled with {@code 429} + {@code Retry-After} once
 * the default 10/60s bucket drains. Drives the server over HTTP like {@code
 * SecurityConfigurationTest}. Requires Docker (Testcontainers).
 *
 * <p>The default login limit is 10 per 60s and no other test hits {@code /api/v1/auth/login}, so
 * the shared service's bucket for the loopback client starts full: the first ten attempts get the
 * normal {@code 401} (bad credentials), the eleventh is rejected before the controller runs.
 */
class RateLimitIT extends AbstractIntegrationTest {

  private static final int LOGIN_LIMIT = 10;
  private static final String BODY =
      "{\"usernameOrEmail\":\"nobody@example.com\",\"password\":\"wrong-password\"}";

  @LocalServerPort int port;

  private HttpResponse<String> postLogin() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(BODY))
            .build();
    return HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
  }

  @Test
  void throttlesLoginBurstWith429AndRetryAfter() throws Exception {
    for (int attempt = 1; attempt <= LOGIN_LIMIT; attempt++) {
      assertThat(postLogin().statusCode())
          .as("attempt %d within the limit must not be rate-limited", attempt)
          .isNotEqualTo(429);
    }

    HttpResponse<String> throttled = postLogin();

    assertThat(throttled.statusCode()).isEqualTo(429);
    assertThat(throttled.headers().firstValue("Retry-After")).isPresent();
  }
}
