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
import io.qnop.service.oidc.OidcOutboundHttpConfiguration.GuardedIdTokenDecoderFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * The ID-token decoder factory of the guarded OIDC login flow (issue #904): decoders are cached per
 * registration instance, a missing JWK-set URI is an authentication error, and the JWK set is
 * fetched through the guarded transport.
 */
class GuardedIdTokenDecoderFactoryTest {

  private static final HttpClientProperties DEFAULTS =
      new HttpClientProperties(null, null, null, null, null);
  private static final HostResolver TO_LOOPBACK =
      host -> new InetAddress[] {InetAddress.getLoopbackAddress()};

  private HttpServer idp;
  private final List<String> received = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws IOException {
    idp = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    idp.createContext(
        "/",
        exchange -> {
          received.add(exchange.getRequestURI().getPath());
          byte[] body = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    idp.start();
  }

  @AfterEach
  void stop() {
    idp.stop(0);
  }

  private GuardedIdTokenDecoderFactory factory(boolean allowPrivate) {
    return new GuardedIdTokenDecoderFactory(
        OidcOutboundHttpConfiguration.guardedRestTemplate(
            OutboundHttpClients.requestFactory(DEFAULTS, allowPrivate, TO_LOOPBACK)));
  }

  private ClientRegistration registration(String jwkSetUri) {
    return ClientRegistration.withRegistrationId("idp")
        .clientId("client")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .authorizationUri("https://idp.example.test/auth")
        .tokenUri("https://idp.example.test/token")
        .jwkSetUri(jwkSetUri)
        .build();
  }

  private String jwkSetUri() {
    return "http://idp.example.test:" + idp.getAddress().getPort() + "/certs";
  }

  /** A syntactically valid RS256 JWS; verifying it makes the decoder fetch the JWK set. */
  private static String anyRs256Token() {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    return b64.encodeToString("{\"alg\":\"RS256\",\"kid\":\"k\"}".getBytes(StandardCharsets.UTF_8))
        + "."
        + b64.encodeToString("{\"sub\":\"x\"}".getBytes(StandardCharsets.UTF_8))
        + "."
        + b64.encodeToString("signature".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("the decoder is reused for the same registration and rebuilt for a new one")
  void cachesPerRegistrationInstance() {
    GuardedIdTokenDecoderFactory factory = factory(true);
    ClientRegistration first = registration(jwkSetUri());

    JwtDecoder decoder = factory.createDecoder(first);

    assertThat(factory.createDecoder(first)).isSameAs(decoder);
    assertThat(factory.createDecoder(registration(jwkSetUri()))).isNotSameAs(decoder);
  }

  @Test
  @DisplayName("a registration without a JWK-set URI is an authentication error")
  void missingJwkSetUri() {
    assertThatThrownBy(() -> factory(true).createDecoder(registration(null)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("JWK Set URI");
  }

  @Test
  @DisplayName("with the opt-in, the JWK set is fetched over the guarded transport")
  void fetchesJwkSetThroughTheGuard() {
    JwtDecoder decoder = factory(true).createDecoder(registration(jwkSetUri()));

    assertThatThrownBy(() -> decoder.decode(anyRs256Token())).isInstanceOf(JwtException.class);
    // Nimbus refetches once on an unknown key id; every fetch went to the JWK-set path.
    assertThat(received).isNotEmpty().containsOnly("/certs");
  }

  @Test
  @DisplayName("without the opt-in, a JWK-set host resolving to a private address is not contacted")
  void privateJwkSetHostIsRefused() {
    JwtDecoder decoder = factory(false).createDecoder(registration(jwkSetUri()));

    assertThatThrownBy(() -> decoder.decode(anyRs256Token())).isInstanceOf(JwtException.class);
    assertThat(received).isEmpty();
  }
}
