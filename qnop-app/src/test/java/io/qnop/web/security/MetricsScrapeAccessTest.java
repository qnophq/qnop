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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

/** Who may read the Prometheus scrape without an admin login (issue #962). */
class MetricsScrapeAccessTest {

  private static final String TOKEN = "scrape-token-0123456789-0123456789-abcdef";
  private static final int APP_PORT = 8080;
  private static final int MANAGEMENT_PORT = 9090;

  private static MockHttpServletRequest scrape(int localPort) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
    request.setLocalPort(localPort);
    return request;
  }

  private static MockHttpServletRequest withToken(MockHttpServletRequest request, String token) {
    request.addHeader("Authorization", "Bearer " + token);
    return request;
  }

  private static MetricsScrapeAccess access(String token, MockEnvironment environment) {
    return new MetricsScrapeAccess(new MetricsScrapeProperties(token), environment);
  }

  @Nested
  @DisplayName("by default")
  class Default {

    private final MetricsScrapeAccess access = access(null, new MockEnvironment());

    @Test
    @DisplayName("claims nothing: the scrape stays with the admin rule")
    void claimsNothing() {
      assertThat(access.matches(scrape(APP_PORT))).isFalse();
      assertThat(access.matches(withToken(scrape(APP_PORT), TOKEN))).isFalse();
      assertThat(access.matches(withToken(scrape(APP_PORT), ""))).isFalse();
    }
  }

  @Nested
  @DisplayName("with a scrape token")
  class ScrapeToken {

    private final MetricsScrapeAccess access = access(TOKEN, new MockEnvironment());

    @Test
    @DisplayName("lets the matching bearer token through")
    void acceptsTheToken() {
      assertThat(access.matches(withToken(scrape(APP_PORT), TOKEN))).isTrue();
    }

    @Test
    @DisplayName("refuses a wrong, truncated, extended or missing token")
    void refusesAnythingElse() {
      assertThat(access.matches(scrape(APP_PORT))).isFalse();
      assertThat(access.matches(withToken(scrape(APP_PORT), "wrong"))).isFalse();
      assertThat(access.matches(withToken(scrape(APP_PORT), TOKEN.substring(1)))).isFalse();
      assertThat(access.matches(withToken(scrape(APP_PORT), TOKEN + "x"))).isFalse();
      assertThat(access.matches(withToken(scrape(APP_PORT), ""))).isFalse();
    }

    @Test
    @DisplayName("recognises its own token, for callers that must keep it away from add-ons")
    void recognisesItsToken() {
      assertThat(access.isScrapeToken(TOKEN)).isTrue();
      assertThat(access.isScrapeToken(TOKEN + "x")).isFalse();
      assertThat(access.isScrapeToken(null)).isFalse();
      assertThat(access(null, new MockEnvironment()).isScrapeToken(TOKEN)).isFalse();
    }

    @Test
    @DisplayName("refuses the token under another scheme")
    void refusesOtherSchemes() {
      MockHttpServletRequest request = scrape(APP_PORT);
      request.addHeader("Authorization", "Basic " + TOKEN);

      assertThat(access.matches(request)).isFalse();
    }

    @Test
    @DisplayName("opens the scrape only: no other path, no other method")
    void opensNothingElse() {
      for (String path :
          new String[] {
            "/actuator",
            "/actuator/health",
            "/actuator/prometheus/",
            "/actuator/prometheus/x",
            "/actuator/env",
            "/api/v1/admin/users",
            "/"
          }) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        assertThat(access.matches(withToken(request, TOKEN))).as(path).isFalse();
      }
      MockHttpServletRequest post = new MockHttpServletRequest("POST", "/actuator/prometheus");
      assertThat(access.matches(withToken(post, TOKEN))).isFalse();
    }
  }

  @Nested
  @DisplayName("with a management port")
  class ManagementPort {

    private MockEnvironment environment(String managementPort) {
      return new MockEnvironment()
          .withProperty("server.port", String.valueOf(APP_PORT))
          .withProperty("management.server.port", managementPort);
    }

    @Test
    @DisplayName("lets a scrape arriving on that port through without a credential")
    void acceptsTheManagementPort() {
      MetricsScrapeAccess access = access(null, environment(String.valueOf(MANAGEMENT_PORT)));

      assertThat(access.matches(scrape(MANAGEMENT_PORT))).isTrue();
    }

    @Test
    @DisplayName("still refuses a scrape arriving on the application port")
    void refusesTheApplicationPort() {
      MetricsScrapeAccess access = access(null, environment(String.valueOf(MANAGEMENT_PORT)));

      assertThat(access.matches(scrape(APP_PORT))).isFalse();
    }

    @Test
    @DisplayName("grants nothing when the management port is the application port")
    void sharedPortGrantsNothing() {
      MetricsScrapeAccess access = access(null, environment(String.valueOf(APP_PORT)));

      assertThat(access.matches(scrape(APP_PORT))).isFalse();
    }

    @Test
    @DisplayName("grants nothing when Actuator's web server is switched off (-1)")
    void disabledManagementServerGrantsNothing() {
      MetricsScrapeAccess access = access(null, environment("-1"));

      assertThat(access.matches(scrape(APP_PORT))).isFalse();
    }

    @Test
    @DisplayName("follows a random port (0) to the one the server actually got")
    void followsARandomPort() {
      MockEnvironment environment = environment("0");
      MetricsScrapeAccess access = access(null, environment);
      assertThat(access.matches(scrape(MANAGEMENT_PORT))).as("before the port is known").isFalse();

      environment.setProperty("local.management.port", String.valueOf(MANAGEMENT_PORT));

      assertThat(access.matches(scrape(MANAGEMENT_PORT))).isTrue();
      assertThat(access.matches(scrape(APP_PORT))).isFalse();
    }

    @Test
    @DisplayName("ignores the Host header's port: only the socket counts")
    void ignoresTheHostHeader() {
      MetricsScrapeAccess access = access(null, environment(String.valueOf(MANAGEMENT_PORT)));
      MockHttpServletRequest request = scrape(APP_PORT);
      request.setServerPort(MANAGEMENT_PORT);
      request.addHeader("Host", "qnop.example:" + MANAGEMENT_PORT);

      assertThat(access.matches(request)).isFalse();
    }
  }
}
