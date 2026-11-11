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
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The connection-time half of the outbound guard (issue #904): the guarded client connects only to
 * addresses it validated itself, whatever an earlier lookup returned, and follows no redirect.
 *
 * <p>A loopback server stands in for an internal service. The injected resolver maps the test host
 * name onto it, so "did the request arrive" is exactly "did the client connect to the private
 * address".
 */
class GuardedOutboundClientTest {

  private static final String HOST = "idp.example.test";
  private static final HttpClientProperties DEFAULTS =
      new HttpClientProperties(null, null, null, null, null);

  private HttpServer internal;
  private final List<String> received = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws IOException {
    internal = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    internal.createContext(
        "/",
        exchange -> {
          received.add(
              exchange.getRequestURI().getPath()
                  + " Host="
                  + exchange.getRequestHeaders().getFirst("Host"));
          if (exchange.getRequestURI().getPath().equals("/redirect")) {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest");
            exchange.sendResponseHeaders(302, -1);
          } else {
            exchange.sendResponseHeaders(204, -1);
          }
          exchange.close();
        });
    internal.start();
  }

  @AfterEach
  void stop() {
    internal.stop(0);
  }

  private String url(String path) {
    return "http://" + HOST + ":" + internal.getAddress().getPort() + path;
  }

  private static InetAddress address(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("a name that answers public first and private later never reaches the private one")
  void rebindingNeverConnectsToThePrivateAddress() throws IOException {
    AtomicInteger lookups = new AtomicInteger();
    HostResolver rebinding =
        host ->
            lookups.getAndIncrement() == 0
                ? new InetAddress[] {address("93.184.216.34")}
                : new InetAddress[] {InetAddress.getLoopbackAddress()};

    // An earlier, separate check (as a caller might make) sees only the public answer …
    assertThat(OutboundAddressPolicy.isBlocked(rebinding.resolve(HOST)[0])).isFalse();

    // … and the connection, which resolves on its own, is refused rather than sent to loopback.
    try (CloseableHttpClient client = OutboundHttpClients.create(DEFAULTS, false, rebinding)) {
      assertThatThrownBy(
              () -> client.execute(new HttpGet(url("/")), response -> response.getCode()))
          .isInstanceOf(BlockedOutboundAddressException.class);
    }
    assertThat(received).as("nothing reached the internal service").isEmpty();
  }

  @Test
  @DisplayName("one blocked address among several refuses the whole host")
  void mixedAnswerIsRefused() throws IOException {
    HostResolver mixed =
        host -> new InetAddress[] {address("93.184.216.34"), InetAddress.getLoopbackAddress()};

    try (CloseableHttpClient client = OutboundHttpClients.create(DEFAULTS, false, mixed)) {
      assertThatThrownBy(
              () -> client.execute(new HttpGet(url("/")), response -> response.getCode()))
          .isInstanceOf(BlockedOutboundAddressException.class);
    }
    assertThat(received).isEmpty();
  }

  @Test
  @DisplayName("an IP literal in the URL is checked at connection time too")
  void literalLoopbackIsRefused() throws IOException {
    try (CloseableHttpClient client = OutboundHttpClients.create(DEFAULTS, false)) {
      String literal = "http://127.0.0.1:" + internal.getAddress().getPort() + "/";
      assertThatThrownBy(() -> client.execute(new HttpGet(literal), response -> response.getCode()))
          .isInstanceOf(BlockedOutboundAddressException.class);
    }
    assertThat(received).isEmpty();
  }

  @Test
  @DisplayName(
      "with the opt-in, the client connects to the resolved address and keeps the Host name")
  void optInConnectsToTheValidatedAddress() throws IOException {
    HostResolver toLoopback = host -> new InetAddress[] {InetAddress.getLoopbackAddress()};

    try (CloseableHttpClient client = OutboundHttpClients.create(DEFAULTS, true, toLoopback)) {
      int status = client.execute(new HttpGet(url("/ok")), response -> response.getCode());
      assertThat(status).isEqualTo(204);
    }
    assertThat(received)
        .containsExactly("/ok Host=" + HOST + ":" + internal.getAddress().getPort());
  }

  @Test
  @DisplayName("a redirect is returned to the caller, never followed")
  void redirectsAreNotFollowed() throws IOException {
    HostResolver toLoopback = host -> new InetAddress[] {InetAddress.getLoopbackAddress()};

    try (CloseableHttpClient client = OutboundHttpClients.create(DEFAULTS, true, toLoopback)) {
      int status = client.execute(new HttpGet(url("/redirect")), response -> response.getCode());
      assertThat(status).isEqualTo(302);
    }
    assertThat(received).hasSize(1);
  }
}
