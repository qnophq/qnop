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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

/**
 * Actuator on its own port (issue #962, ADR-0065): a scrape arriving there needs no credential,
 * because the port is not published and reaching it is the access control. The application port
 * gains nothing from the setting.
 *
 * <p>Driven over real sockets: which port a request arrived on is the whole point, and a mock
 * request has none.
 */
@TestPropertySource(properties = "management.server.port=0")
class ManagementPortIT extends AbstractIntegrationTest {

  private final HttpClient http = HttpClient.newHttpClient();

  @Autowired private Environment environment;

  private int port(String property) {
    return environment.getRequiredProperty(property, Integer.class);
  }

  private HttpResponse<String> get(int port, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("the management port serves the scrape without a credential")
  void managementPortServesTheScrape() throws Exception {
    HttpResponse<String> response = get(port("local.management.port"), "/actuator/prometheus");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("qnop_jobs");
  }

  @Test
  @DisplayName("the management port serves health for probes")
  void managementPortServesHealth() throws Exception {
    HttpResponse<String> response = get(port("local.management.port"), "/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).doesNotContain("components");
  }

  @Test
  @DisplayName("the application port does not serve the scrape anonymously")
  void applicationPortStaysClosed() throws Exception {
    HttpResponse<String> response = get(port("local.server.port"), "/actuator/prometheus");

    // Actuator has left this port, but the answer is still decided by security first: an
    // anonymous caller is told to authenticate, not which paths exist.
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).doesNotContain("qnop_jobs");
  }

  @Test
  @DisplayName("the management port opens the scrape only: nothing else is served without a login")
  void managementPortOpensNothingElse() throws Exception {
    int management = port("local.management.port");

    assertThat(get(management, "/actuator/prometheus/").statusCode()).isEqualTo(401);
    assertThat(get(management, "/actuator/env").statusCode()).isEqualTo(401);
    assertThat(get(management, "/api/v1/admin/users").statusCode()).isEqualTo(401);
    HttpRequest post =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + management + "/actuator/prometheus"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    assertThat(http.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
  }
}
