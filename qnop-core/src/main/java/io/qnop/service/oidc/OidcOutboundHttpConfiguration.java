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

import io.qnop.service.http.HttpClientProperties;
import io.qnop.service.http.OutboundHttpClients;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Routes every request of the OIDC/OAuth2 login flow through the SSRF-guarded client (issue #904).
 *
 * <p>Spring Security's defaults each build their own client: the token endpoint a {@code
 * RestClient} on the framework's default request factory, user-info and the ID-token JWK set a
 * {@code new RestTemplate()} over {@code HttpURLConnection} — each resolving the provider's host by
 * itself, after (and independently of) any check qnop made, and the latter following redirects. The
 * {@code oauth2Login} configurer picks up beans of these four types, so declaring them here
 * replaces each default without touching the filter chain. Each is configured as Spring Security
 * configures its own default (message converters, error handler, ID-token validators); only the
 * transport differs.
 */
@Configuration(proxyBeanMethods = false)
public class OidcOutboundHttpConfiguration {

  @Bean
  OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> guardedTokenResponseClient(
      OidcSsrfPolicy ssrfPolicy, HttpClientProperties httpProperties) {
    RestClientAuthorizationCodeTokenResponseClient client =
        new RestClientAuthorizationCodeTokenResponseClient();
    client.setRestClient(
        RestClient.builder()
            .requestFactory(requestFactory(ssrfPolicy, httpProperties))
            .configureMessageConverters(
                converters ->
                    converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build());
    return client;
  }

  @Bean
  OAuth2UserService<OAuth2UserRequest, OAuth2User> guardedOAuth2UserService(
      OidcSsrfPolicy ssrfPolicy, HttpClientProperties httpProperties) {
    return userService(guardedRestTemplate(ssrfPolicy, httpProperties));
  }

  @Bean
  OAuth2UserService<OidcUserRequest, OidcUser> guardedOidcUserService(
      OidcSsrfPolicy ssrfPolicy, HttpClientProperties httpProperties) {
    OidcUserService service = new OidcUserService();
    service.setOauth2UserService(userService(guardedRestTemplate(ssrfPolicy, httpProperties)));
    return service;
  }

  @Bean
  JwtDecoderFactory<ClientRegistration> guardedIdTokenDecoderFactory(
      OidcSsrfPolicy ssrfPolicy, HttpClientProperties httpProperties) {
    return new GuardedIdTokenDecoderFactory(guardedRestTemplate(ssrfPolicy, httpProperties));
  }

  /** The guarded transport; honours the deployment's private-IdP opt-in. */
  private static ClientHttpRequestFactory requestFactory(
      OidcSsrfPolicy ssrfPolicy, HttpClientProperties httpProperties) {
    return OutboundHttpClients.requestFactory(httpProperties, ssrfPolicy.allowsPrivate());
  }

  private static DefaultOAuth2UserService userService(RestTemplate rest) {
    DefaultOAuth2UserService service = new DefaultOAuth2UserService();
    service.setRestOperations(rest);
    return service;
  }

  private static RestTemplate guardedRestTemplate(
      OidcSsrfPolicy ssrfPolicy, HttpClientProperties httpProperties) {
    RestTemplate rest = new RestTemplate(requestFactory(ssrfPolicy, httpProperties));
    rest.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
    return rest;
  }

  /**
   * {@link OidcIdTokenDecoderFactory}'s default behaviour — RS256 over the provider's JWK set, the
   * timestamp and OIDC ID-token validators, the default claim conversion — with the JWK set fetched
   * through the guarded client ({@code OidcIdTokenDecoderFactory} offers no way to supply one).
   * Decoders are cached per registration <em>instance</em>, so a provider edit (which rebuilds the
   * registration) never reuses a decoder built for an old client id or JWK-set URI.
   */
  static final class GuardedIdTokenDecoderFactory implements JwtDecoderFactory<ClientRegistration> {

    private record Cached(ClientRegistration registration, JwtDecoder decoder) {}

    private final RestTemplate rest;
    private final Map<String, Cached> decoders = new ConcurrentHashMap<>();

    GuardedIdTokenDecoderFactory(RestTemplate rest) {
      this.rest = rest;
    }

    @Override
    public JwtDecoder createDecoder(ClientRegistration registration) {
      return decoders
          .compute(
              registration.getRegistrationId(),
              (id, cached) ->
                  cached != null && cached.registration() == registration
                      ? cached
                      : new Cached(registration, build(registration)))
          .decoder();
    }

    private JwtDecoder build(ClientRegistration registration) {
      String jwkSetUri = registration.getProviderDetails().getJwkSetUri();
      if (jwkSetUri == null || jwkSetUri.isBlank()) {
        String message =
            "No JWK Set URI is configured for client registration '"
                + registration.getRegistrationId()
                + "', so its ID token cannot be verified";
        throw new OAuth2AuthenticationException(
            new OAuth2Error("missing_signature_verifier", message, null), message);
      }
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
              .jwsAlgorithm(SignatureAlgorithm.RS256)
              .restOperations(rest)
              .build();
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              new JwtTimestampValidator(), new OidcIdTokenValidator(registration)));
      decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
      return decoder;
    }
  }
}
