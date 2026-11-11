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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * OIDC discovery over the guarded client (issue #904): the document is fetched from the validated
 * address only, redirects are not followed, and the issuer must match.
 */
class OidcDiscoveryClientTest {

  private static final HttpClientProperties DEFAULTS =
      new HttpClientProperties(null, null, null, null, null);
  private static final HostResolver TO_LOOPBACK =
      host -> new InetAddress[] {InetAddress.getLoopbackAddress()};

  private HttpServer idp;
  private String issuer;
  private final List<String> received = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws IOException {
    idp = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    issuer = "http://idp.example.test:" + idp.getAddress().getPort() + "/realms/qnop";
    idp.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          received.add(path);
          if (path.startsWith("/moved")) {
            exchange
                .getResponseHeaders()
                .add("Location", issuer + "/.well-known/openid-configuration");
            exchange.sendResponseHeaders(302, -1);
          } else if (path.startsWith("/missing")) {
            exchange.sendResponseHeaders(404, -1);
          } else {
            String advertised =
                path.startsWith("/other")
                    ? "https://other.example"
                    : path.startsWith("/slash") ? origin() + "/slash/" : issuer;
            byte[] body = document(advertised);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    idp.start();
  }

  @AfterEach
  void stop() {
    idp.stop(0);
  }

  private byte[] document(String advertisedIssuer) {
    String base = issuer;
    return ("{\"issuer\":\""
            + advertisedIssuer
            + "\",\"authorization_endpoint\":\""
            + base
            + "/auth\",\"token_endpoint\":\""
            + base
            + "/token\",\"userinfo_endpoint\":\""
            + base
            + "/userinfo\",\"jwks_uri\":\""
            + base
            + "/certs\",\"response_types_supported\":[\"code\"],"
            + "\"subject_types_supported\":[\"public\"],"
            + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private OidcDiscoveryClient client(boolean allowPrivate) {
    return new OidcDiscoveryClient(
        OutboundHttpClients.requestFactory(DEFAULTS, allowPrivate, TO_LOOPBACK));
  }

  @Test
  @DisplayName("with the private-IdP opt-in, discovery pre-fills the endpoints")
  void discoversEndpoints() {
    ClientRegistration registration =
        client(true).fromIssuer(issuer).registrationId("r").clientId("c").build();

    assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo(issuer + "/token");
    assertThat(registration.getProviderDetails().getJwkSetUri()).isEqualTo(issuer + "/certs");
    assertThat(received).containsExactly("/realms/qnop/.well-known/openid-configuration");
  }

  @Test
  @DisplayName("without the opt-in, a host resolving to a private address is never contacted")
  void privateAddressIsRefused() {
    assertThatThrownBy(() -> client(false).fromIssuer(issuer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blocked");
    assertThat(received).isEmpty();
  }

  @Test
  @DisplayName("a redirect from the discovery endpoint is not followed")
  void redirectIsNotFollowed() {
    String moved = "http://idp.example.test:" + idp.getAddress().getPort() + "/moved";

    assertThatThrownBy(() -> client(true).fromIssuer(moved))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("302");
    assertThat(received).containsExactly("/moved/.well-known/openid-configuration");
  }

  @Test
  @DisplayName("a document naming another issuer is rejected")
  void issuerMismatchIsRejected() {
    String other = "http://idp.example.test:" + idp.getAddress().getPort() + "/other";

    assertThatThrownBy(() -> client(true).fromIssuer(other))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
  }

  private String origin() {
    return "http://idp.example.test:" + idp.getAddress().getPort();
  }

  @Test
  @DisplayName("an issuer with a trailing slash must match exactly and is fetched without '//'")
  void trailingSlashIssuer() {
    String slashed = origin() + "/slash/";

    ClientRegistration registration =
        client(true).fromIssuer(slashed).registrationId("r").clientId("c").build();

    assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(slashed);
    assertThat(received).containsExactly("/slash/.well-known/openid-configuration");
  }

  @Test
  @DisplayName("an issuer with a query or fragment is refused before any request")
  void issuerWithQueryOrFragmentIsRefused() {
    assertThatThrownBy(() -> client(true).fromIssuer(issuer + "?tenant=a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("query or fragment");
    assertThatThrownBy(() -> client(true).fromIssuer(issuer + "#frag"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("query or fragment");
    assertThat(received).isEmpty();
  }

  @Test
  @DisplayName("a missing discovery document names the location that was tried")
  void notFoundNamesTheLocation() {
    String missing = origin() + "/missing";

    assertThatThrownBy(() -> client(true).fromIssuer(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("404")
        .hasMessageContaining(missing + "/.well-known/openid-configuration");
  }
}
