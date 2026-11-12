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
package io.qnop.service.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.qnop.service.http.HostResolver;
import io.qnop.service.http.HttpClientProperties;
import io.qnop.service.http.OutboundHttpClients;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GitHub e-mail lookup over the guarded OIDC client (issue #946): the connection-time address
 * guard and the explicit proxy opt-in apply to it exactly as to every other OIDC request, and the
 * verified/primary selection is unchanged.
 */
class GitHubEmailFetcherTest {

  private static final String HOST = "api.github.test";
  private static final HttpClientProperties DIRECT =
      new HttpClientProperties(null, null, null, null, null, null);
  private static final HostResolver TO_LOOPBACK =
      host -> new InetAddress[] {InetAddress.getLoopbackAddress()};

  /** In proxy mode the client must never resolve a proxied destination itself. */
  private static final HostResolver NEVER =
      host -> {
        throw new AssertionError("the client resolved a proxied destination itself: " + host);
      };

  private HttpServer server;
  private final AtomicReference<String> body = new AtomicReference<>("[]");
  private final AtomicInteger status = new AtomicInteger(200);
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.add(exchange.getRequestURI().toString());
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          if (status.get() == 302) {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
          } else {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String endpoint() {
    return "http://" + HOST + ":" + server.getAddress().getPort() + "/user/emails";
  }

  private GitHubEmailFetcher direct(boolean allowPrivate) {
    return new GitHubEmailFetcher(
        OutboundHttpClients.requestFactory(DIRECT, allowPrivate, TO_LOOPBACK), endpoint());
  }

  private HttpClientProperties viaTestProxy() {
    return new HttpClientProperties(
        null,
        null,
        null,
        null,
        null,
        new HttpClientProperties.Proxy("127.0.0.1", server.getAddress().getPort(), List.of()));
  }

  @Test
  @DisplayName("the primary verified address wins over an earlier verified one")
  void primaryVerifiedWins() {
    body.set(
        "[{\"email\":\"a@x.test\",\"verified\":true,\"primary\":false},"
            + "{\"email\":\"p@x.test\",\"verified\":true,\"primary\":true}]");

    assertThat(direct(true).fetchPrimaryEmail("tok")).isEqualTo("p@x.test");
    assertThat(authorizations).containsExactly("Bearer tok");
  }

  @Test
  @DisplayName("an unverified primary is skipped; the first verified address is the fallback")
  void unverifiedPrimaryIsSkipped() {
    body.set(
        "[{\"email\":\"p@x.test\",\"verified\":false,\"primary\":true},"
            + "{\"verified\":true,\"primary\":true},"
            + "{\"email\":\"v1@x.test\",\"verified\":true},"
            + "{\"email\":\"v2@x.test\",\"verified\":true}]");

    assertThat(direct(true).fetchPrimaryEmail("tok")).isEqualTo("v1@x.test");
  }

  @Test
  @DisplayName("no verified address yields null")
  void noVerifiedAddress() {
    body.set("[{\"email\":\"p@x.test\",\"verified\":false,\"primary\":true}]");

    assertThat(direct(true).fetchPrimaryEmail("tok")).isNull();
  }

  @Test
  @DisplayName("an error status, a malformed body or a redirect yields null, not an exception")
  void failuresYieldNull() {
    status.set(401);
    assertThat(direct(true).fetchPrimaryEmail("tok")).isNull();

    status.set(200);
    body.set("{not json");
    assertThat(direct(true).fetchPrimaryEmail("tok")).isNull();

    status.set(302);
    assertThat(direct(true).fetchPrimaryEmail("tok")).isNull();
    assertThat(requests).as("the redirect was not followed").hasSize(3);
  }

  @Test
  @DisplayName("a response over the size limit yields null")
  void oversizedResponseYieldsNull() {
    body.set(
        "[{\"email\":\"p@x.test\",\"verified\":true,\"primary\":true,\"pad\":\""
            + "a".repeat(OutboundHttpClients.MAX_RESPONSE_BYTES)
            + "\"}]");

    assertThat(direct(true).fetchPrimaryEmail("tok")).isNull();
  }

  @Test
  @DisplayName("direct mode: a destination resolving to a private address is never contacted")
  void privateDestinationIsRefusedWithoutOptIn() {
    body.set("[{\"email\":\"p@x.test\",\"verified\":true,\"primary\":true}]");

    assertThat(direct(false).fetchPrimaryEmail("tok")).isNull();
    assertThat(requests).isEmpty();
  }

  @Test
  @DisplayName("proxy mode: the lookup leaves through the configured proxy, unresolved locally")
  void routesThroughTheConfiguredProxy() {
    body.set("[{\"email\":\"p@x.test\",\"verified\":true,\"primary\":true}]");
    // The test server plays the forward proxy: it receives the absolute URI in the request line.
    String proxied = "http://" + HOST + "/user/emails";
    GitHubEmailFetcher fetcher =
        new GitHubEmailFetcher(
            OutboundHttpClients.requestFactory(viaTestProxy(), false, NEVER), proxied);

    assertThat(fetcher.fetchPrimaryEmail("tok")).isEqualTo("p@x.test");
    assertThat(requests).containsExactly(proxied);
    assertThat(authorizations).containsExactly("Bearer tok");
  }

  @Test
  @DisplayName("proxy mode: a blocked destination is refused before it reaches the proxy")
  void proxyModeKeepsTheLexicalGuard() {
    GitHubEmailFetcher fetcher =
        new GitHubEmailFetcher(
            OutboundHttpClients.requestFactory(viaTestProxy(), false, NEVER),
            "http://169.254.169.254/user/emails");

    assertThat(fetcher.fetchPrimaryEmail("tok")).isNull();
    assertThat(requests).isEmpty();
  }
}
