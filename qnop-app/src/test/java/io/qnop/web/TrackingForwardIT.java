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
package io.qnop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The whole path, end to end, against a stand-in analytics backend (issue #666).
 *
 * <p>Everything else about tracking is asserted in pieces; this is the test that says the pieces
 * are connected — a browser's request reaches the configured backend, and what arrives there has
 * had the document id and the page title taken out of it on the way.
 *
 * <p>The stand-in is a plain JDK HTTP server on loopback, which is exactly the address the SSRF
 * guard blocks by default: the deployment property that permits it is set here, and that is the
 * whole reason the property exists rather than being a setting.
 */
@TestPropertySource(properties = {"qnop.tracking.allow-private-host=true"})
class TrackingForwardIT extends SeededIntegrationTest {

  private static final String DOC = "8f3c1d2e-4a5b-6c7d-8e9f-0a1b2c3d4e5f";

  @Autowired private ApplicationSettingsService settings;

  private HttpServer backend;
  private final BlockingQueue<String> received = new ArrayBlockingQueue<>(8);

  @BeforeEach
  void startBackendAndConfigure() throws IOException {
    backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    backend.createContext(
        "/script.js",
        exchange -> {
          byte[] body = "window.umami={};".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/javascript");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    backend.createContext(
        "/api/send",
        exchange -> {
          try (InputStream in = exchange.getRequestBody()) {
            received.offer(new String(in.readAllBytes(), StandardCharsets.UTF_8));
          }
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    backend.start();

    settings.update(
        Map.of(
            ApplicationSettingKey.TRACKING_ENABLED.getKey(), "true",
            ApplicationSettingKey.TRACKING_PROVIDER.getKey(), "umami",
            ApplicationSettingKey.TRACKING_HOST.getKey(),
                "http://127.0.0.1:" + backend.getAddress().getPort(),
            ApplicationSettingKey.TRACKING_SITE_ID.getKey(), "site-1"),
        null);
  }

  @AfterEach
  void stopBackendAndReset() {
    backend.stop(0);
    settings.update(
        Map.of(
            ApplicationSettingKey.TRACKING_ENABLED.getKey(), "false",
            ApplicationSettingKey.TRACKING_PROVIDER.getKey(), "none",
            ApplicationSettingKey.TRACKING_HOST.getKey(), "",
            ApplicationSettingKey.TRACKING_SITE_ID.getKey(), ""),
        null);
  }

  @Test
  @DisplayName("the vendor script is served from this origin")
  void servesTheVendorScript() throws Exception {
    // The whole reason the proxy exists: the browser asks this origin, so the
    // CSP never has to name the analytics host.
    mockMvc
        .perform(get("/t/s.js"))
        .andExpect(status().isOk())
        .andExpect(content().string("window.umami={};"));
  }

  @Test
  @DisplayName("a measurement arrives at the backend with the id and the title removed")
  void forwardsSanitizedMeasurement() throws Exception {
    String body =
        "{\"type\":\"event\",\"payload\":{\"website\":\"site-1\",\"title\":\"Vendor agreement\","
            + "\"url\":\"/reviews/"
            + DOC
            + "/tasks?filter=open\"}}";

    mockMvc
        .perform(post("/t/c/api/send").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNoContent());

    String arrived = received.poll(5, TimeUnit.SECONDS);
    assertThat(arrived).as("the backend received a measurement").isNotNull();
    // Three things had to happen on the way, and all three are about the same
    // risk: the id of a customer's document must not end up in a report.
    assertThat(arrived).doesNotContain(DOC);
    assertThat(arrived).doesNotContain("Vendor agreement");
    assertThat(arrived).doesNotContain("filter=open");
    assertThat(arrived).contains("/reviews/:id/tasks").contains("site-1");
  }

  @Test
  @DisplayName("a path the provider does not declare never reaches the backend")
  void refusesUndeclaredPaths() throws Exception {
    // PostHog's session-recording path is the case that matters, but the rule is
    // general: anything not on the provider's list is dropped here, silently.
    mockMvc
        .perform(
            post("/t/c/api/admin/websites").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNoContent());

    assertThat(received.poll(1, TimeUnit.SECONDS))
        .as("nothing should have been forwarded")
        .isNull();
  }
}
