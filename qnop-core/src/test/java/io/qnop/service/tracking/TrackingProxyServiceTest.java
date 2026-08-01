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
package io.qnop.service.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.qnop.service.http.HttpClientProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Forwarding, against a stand-in backend (issue #666).
 *
 * <p>The case that earns this test: <b>Matomo and Pirsch measure with GET</b>, the others with
 * POST. A proxy that took only POST worked for three providers out of five, and it took pointing
 * the thing at a real Matomo to notice — so the method the browser used is asserted here, at the
 * level where the request is actually built.
 */
class TrackingProxyServiceTest {

  private static final String DOC = "8f3c1d2e-4a5b-6c7d-8e9f-0a1b2c3d4e5f";

  private final TrackingConfigService config = mock(TrackingConfigService.class);

  private HttpServer backend;
  private TrackingProxyService proxy;
  private final BlockingQueue<String> requests = new ArrayBlockingQueue<>(8);

  @BeforeEach
  void startBackend() throws IOException {
    backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    backend.createContext(
        "/",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.offer(
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI().getPath()
                  + (query == null ? "" : "?" + query)
                  + (body.isEmpty() ? "" : " " + body));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    backend.start();

    String host = "http://127.0.0.1:" + backend.getAddress().getPort();
    when(config.current())
        .thenReturn(
            Optional.of(
                new TrackingRuntimeConfig(
                    TrackingProvider.MATOMO,
                    "1",
                    host + "/matomo.js",
                    host,
                    false,
                    false,
                    false,
                    true)));
    proxy = new TrackingProxyService(config, defaults());
  }

  @AfterEach
  void stopBackend() {
    backend.stop(0);
  }

  private static HttpClientProperties defaults() {
    return new HttpClientProperties(null, null, null, null, null);
  }

  @Test
  @DisplayName("a GET measurement travels as a GET, with its query sanitized")
  void forwardsGetWithQuery() throws Exception {
    Optional<Integer> status =
        proxy.forward(
            "GET",
            "/matomo.php",
            "idsite=1&rec=1&url=%2Freviews%2F"
                + DOC
                + "%2Ftasks%3Ffilter%3Dopen&action_name=Vendor",
            new byte[0],
            null,
            "203.0.113.9",
            "Mozilla/5.0");

    assertThat(status).contains(200);
    String seen = requests.poll(5, TimeUnit.SECONDS);
    assertThat(seen).as("the backend saw the measurement").isNotNull();
    assertThat(seen).startsWith("GET /matomo.php?");
    assertThat(seen).doesNotContain(DOC).doesNotContain("filter%3Dopen");
    // Matomo's title field is emptied rather than dropped, so the parameter list
    // keeps the shape the backend expects.
    assertThat(seen).contains("action_name=").doesNotContain("action_name=Vendor");
  }

  @Test
  @DisplayName("a POST measurement travels as a POST, with its body sanitized")
  void forwardsPostWithBody() throws Exception {
    when(config.current())
        .thenReturn(
            Optional.of(
                new TrackingRuntimeConfig(
                    TrackingProvider.UMAMI,
                    "site",
                    "http://127.0.0.1:" + backend.getAddress().getPort() + "/script.js",
                    "http://127.0.0.1:" + backend.getAddress().getPort(),
                    false,
                    false,
                    false,
                    true)));

    proxy.forward(
        "POST",
        "/api/send",
        null,
        ("{\"payload\":{\"url\":\"/reviews/" + DOC + "\",\"title\":\"Vendor agreement\"}}")
            .getBytes(StandardCharsets.UTF_8),
        "application/json",
        "203.0.113.9",
        "Mozilla/5.0");

    String seen = requests.poll(5, TimeUnit.SECONDS);
    assertThat(seen).isNotNull();
    assertThat(seen).startsWith("POST /api/send");
    assertThat(seen)
        .contains("/reviews/:id")
        .doesNotContain(DOC)
        .doesNotContain("Vendor agreement");
  }

  @Test
  @DisplayName("an undeclared path is never sent, whatever the method")
  void refusesUndeclaredPaths() throws Exception {
    assertThat(proxy.forward("GET", "/index.php", "module=API", new byte[0], null, null, null))
        .isEmpty();
    assertThat(proxy.forward("POST", "/matomo.php/../admin", null, new byte[0], null, null, null))
        .isEmpty();

    assertThat(requests.poll(1, TimeUnit.SECONDS)).as("nothing was forwarded").isNull();
  }
}
