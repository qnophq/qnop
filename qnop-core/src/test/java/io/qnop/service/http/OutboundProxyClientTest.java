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
package io.qnop.service.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The explicit outbound proxy opt-in for the guarded clients (issue #940).
 *
 * <p>Two loopback servers: one acts as the forward proxy (an HTTP proxy receives the absolute URI
 * in the request line), the other as a directly reachable internal service. The destination
 * resolver refuses to be asked about anything but the direct-route test host, so "the proxy got the
 * request" also proves the client never resolved the proxied destination itself.
 */
class OutboundProxyClientTest {

  private static final String PROXIED_HOST = "idp.example.test";
  private static final String DIRECT_HOST = "idp.corp.test";

  private HttpServer proxy;
  private HttpServer internal;
  private final List<String> proxied = new CopyOnWriteArrayList<>();
  private final List<String> direct = new CopyOnWriteArrayList<>();

  /** Maps only the direct-route host onto loopback; any other lookup is a test failure. */
  private final HostResolver destinations =
      host -> {
        if (host.equals(DIRECT_HOST)) {
          return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }
        throw new AssertionError("the client resolved a proxied destination itself: " + host);
      };

  @BeforeEach
  void start() throws IOException {
    proxy = recordingServer(proxied);
    internal = recordingServer(direct);
  }

  @AfterEach
  void stop() {
    proxy.stop(0);
    internal.stop(0);
  }

  private static HttpServer recordingServer(List<String> sink) throws IOException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          sink.add(exchange.getRequestURI().toString());
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    return server;
  }

  private HttpClientProperties viaProxy(List<String> nonProxyHosts) {
    return new HttpClientProperties(
        null,
        null,
        null,
        null,
        null,
        new HttpClientProperties.Proxy("127.0.0.1", proxy.getAddress().getPort(), nonProxyHosts));
  }

  private CloseableHttpClient client(HttpClientProperties properties, boolean allowPrivate) {
    return OutboundHttpClients.create(properties, allowPrivate, destinations, HostResolver.SYSTEM);
  }

  private static int get(CloseableHttpClient client, String url) throws IOException {
    return client.execute(new HttpGet(url), response -> response.getCode());
  }

  @Test
  @DisplayName("in proxy mode a request leaves through the proxy, unresolved by the client")
  void routesThroughTheProxy() throws IOException {
    try (CloseableHttpClient client = client(viaProxy(List.of()), false)) {
      assertThat(get(client, "http://" + PROXIED_HOST + "/.well-known/x")).isEqualTo(204);
    }
    assertThat(proxied).containsExactly("http://" + PROXIED_HOST + "/.well-known/x");
    assertThat(direct).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1/",
        "http://localhost/",
        "http://169.254.169.254/latest",
        "http://10.0.0.1/",
        "http://[::1]/",
        "http://2130706433/"
      })
  @DisplayName("in proxy mode the lexical guard still refuses a blocked destination")
  void lexicalGuardStillAppliesInProxyMode(String url) throws IOException {
    try (CloseableHttpClient client = client(viaProxy(List.of()), false)) {
      assertThatThrownBy(() -> get(client, url))
          .isInstanceOf(BlockedOutboundAddressException.class);
    }
    assertThat(proxied).as("nothing was handed to the proxy").isEmpty();
  }

  @Test
  @DisplayName("with the private opt-in, a private destination may leave through the proxy")
  void privateOptInIsHonouredInProxyMode() throws IOException {
    try (CloseableHttpClient client = client(viaProxy(List.of()), true)) {
      assertThat(get(client, "http://localhost/x")).isEqualTo(204);
    }
    assertThat(proxied).containsExactly("http://localhost/x");
  }

  @Test
  @DisplayName("a non-proxy host goes direct and keeps the connection-time address guard")
  void nonProxyHostKeepsTheFullGuard() throws IOException {
    try (CloseableHttpClient client = client(viaProxy(List.of("*.corp.test")), false)) {
      String url = "http://" + DIRECT_HOST + ":" + internal.getAddress().getPort() + "/";
      assertThatThrownBy(() -> get(client, url))
          .isInstanceOf(BlockedOutboundAddressException.class);
    }
    assertThat(proxied).isEmpty();
    assertThat(direct).isEmpty();
  }

  @Test
  @DisplayName("a non-proxy host is connected to directly, bypassing the proxy")
  void nonProxyHostBypassesTheProxy() throws IOException {
    try (CloseableHttpClient client = client(viaProxy(List.of(DIRECT_HOST)), true)) {
      String url = "http://" + DIRECT_HOST + ":" + internal.getAddress().getPort() + "/direct";
      assertThat(get(client, url)).isEqualTo(204);
    }
    assertThat(direct).containsExactly("/direct");
    assertThat(proxied).isEmpty();
  }

  @Test
  @DisplayName("without the property, JVM proxy system properties are still ignored")
  void directModeIgnoresJvmProxySystemProperties() throws IOException {
    String previousHost = System.getProperty("http.proxyHost");
    String previousPort = System.getProperty("http.proxyPort");
    System.setProperty("http.proxyHost", "127.0.0.1");
    System.setProperty("http.proxyPort", Integer.toString(proxy.getAddress().getPort()));
    try (CloseableHttpClient client =
        client(new HttpClientProperties(null, null, null, null, null, null), true)) {
      String url = "http://" + DIRECT_HOST + ":" + internal.getAddress().getPort() + "/direct";
      assertThat(get(client, url)).isEqualTo(204);
    } finally {
      restore("http.proxyHost", previousHost);
      restore("http.proxyPort", previousPort);
    }
    assertThat(direct).containsExactly("/direct");
    assertThat(proxied).isEmpty();
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
