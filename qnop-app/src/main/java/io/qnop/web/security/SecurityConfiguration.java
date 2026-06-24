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

import io.qnop.security.QnopProperties;
import io.qnop.web.ApiErrorWriter;
import io.qnop.web.security.ratelimit.ChangePasswordRateLimitFilter;
import io.qnop.web.security.ratelimit.ForgotPasswordRateLimitFilter;
import io.qnop.web.security.ratelimit.LoginRateLimitFilter;
import io.qnop.web.security.ratelimit.RefreshRateLimitFilter;
import io.qnop.web.security.ratelimit.RegisterRateLimitFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The servlet security filter chain for the Community server (issue #10, ADR-0022).
 *
 * <p>The API is stateless: bearer access tokens are validated by the resource-server filter using
 * {@link DelegatingJwtDecoder} (local HMAC + revocation, issue #17), and unauthenticated requests
 * receive a JSON {@code 401} rather than a login redirect. The login endpoint and actuator health
 * are public; refresh/logout are public but ride on the HttpOnly refresh cookie, so they are
 * CSRF-protected via a double-submit cookie token (the rest of the API is token-based and exempt).
 * Everything else requires authentication. Security headers (a strict CSP for a JSON API,
 * frame-deny, referrer policy, HSTS) are applied to every response. CORS is driven by {@link
 * QnopProperties} so the SPA origin is configurable per environment.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

  private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L; // one year

  /** Cookie-bearing, state-changing auth endpoints that require a double-submit CSRF token. */
  private static final RequestMatcher AUTH_CSRF_MATCHER =
      request ->
          "POST".equalsIgnoreCase(request.getMethod())
              && ("/api/v1/auth/refresh".equals(request.getRequestURI())
                  || "/api/v1/auth/logout".equals(request.getRequestURI()));

  @Bean
  @Order(2)
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      AuthenticationEntryPoint authenticationEntryPoint,
      AccessDeniedHandler accessDeniedHandler,
      CorsConfigurationSource corsConfigurationSource,
      DelegatingJwtDecoder jwtDecoder,
      LoginRateLimitFilter loginRateLimitFilter,
      RefreshRateLimitFilter refreshRateLimitFilter,
      ChangePasswordRateLimitFilter changePasswordRateLimitFilter,
      RegisterRateLimitFilter registerRateLimitFilter,
      ForgotPasswordRateLimitFilter forgotPasswordRateLimitFilter,
      PasswordChangeRequiredFilter passwordChangeRequiredFilter)
      throws Exception {
    http.csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .requireCsrfProtectionMatcher(AUTH_CSRF_MATCHER))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        // Rate-limit auth endpoints (issue #18, ADR-0027). The IP-keyed login/refresh limiters run
        // before CSRF/auth processing so abuse is dropped as cheaply as possible; the
        // change-password
        // limiter runs after bearer authentication (before AuthorizationFilter) so it can key on
        // the
        // authenticated subject.
        .addFilterBefore(loginRateLimitFilter, CsrfFilter.class)
        .addFilterBefore(refreshRateLimitFilter, CsrfFilter.class)
        .addFilterBefore(changePasswordRateLimitFilter, AuthorizationFilter.class)
        // IP-keyed limiters for the public self-service endpoints (issue #20).
        .addFilterBefore(registerRateLimitFilter, CsrfFilter.class)
        .addFilterBefore(forgotPasswordRateLimitFilter, CsrfFilter.class)
        // Force a password change before any non-auth resource (issue #20); needs the
        // authenticated subject, so it runs after the authorization filter.
        .addFilterAfter(passwordChangeRequiredFilter, AuthorizationFilter.class)
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/verify-email",
                        "/api/v1/auth/forgot-password",
                        "/api/v1/auth/reset-password")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/branding/**")
                    .permitAll()
                    // The SPA needs the public server config before authentication
                    // (enabled OIDC providers, self-registration, edition). OpenAPI
                    // declares GET /config as security: [] — honour that here.
                    .requestMatchers(HttpMethod.GET, "/api/v1/config")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(
                    jwt ->
                        jwt.decoder(jwtDecoder)
                            .jwtAuthenticationConverter(new RoleJwtAuthenticationConverter())))
        .httpBasic(httpBasic -> httpBasic.disable())
        .formLogin(formLogin -> formLogin.disable())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(
                        csp ->
                            csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                    .frameOptions(frame -> frame.deny())
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                    .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .httpStrictTransportSecurity(
                        hsts ->
                            hsts.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)));
    return http.build();
  }

  /**
   * Dedicated chain for the OIDC/OAuth2 browser login handshake (issue #21). It is scoped to the
   * authorization-start and callback URLs only, so the main API chain above stays STATELESS. A
   * session may be created here (IF_REQUIRED) to hold the in-flight authorization request between
   * the redirect to the IdP and the callback — this avoids storing (and deserializing) the
   * authorization request in a cookie. On success, {@link OidcLoginSuccessHandler} sets the qnop
   * refresh cookie and redirects to the SPA; the session is irrelevant to the token-based API.
   */
  @Bean
  @Order(1)
  SecurityFilterChain oidcLoginSecurityChain(
      HttpSecurity http,
      ClientRegistrationRepository clientRegistrationRepository,
      OidcLoginSuccessHandler oidcLoginSuccessHandler,
      PromptAwareOAuth2AuthorizationRequestResolver promptAwareResolver,
      CorsConfigurationSource corsConfigurationSource)
      throws Exception {
    http.securityMatcher("/oauth2/authorization/**", "/login/oauth2/code/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .oauth2Login(
            oauth2 ->
                oauth2
                    .clientRegistrationRepository(clientRegistrationRepository)
                    .authorizationEndpoint(
                        endpoint -> endpoint.authorizationRequestResolver(promptAwareResolver))
                    .successHandler(oidcLoginSuccessHandler))
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
    return http.build();
  }

  /** Persists the OAuth2 access token (app-wide, in memory) so the success handler can read it. */
  @Bean
  OAuth2AuthorizedClientService oauth2AuthorizedClientService(
      ClientRegistrationRepository clientRegistrationRepository) {
    return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
  }

  @Bean
  OAuth2AuthorizedClientRepository oauth2AuthorizedClientRepository(
      OAuth2AuthorizedClientService authorizedClientService) {
    return new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(authorizedClientService);
  }

  /** Writes the uniform {@code ErrorResponse} 401 instead of a login redirect (issue #45). */
  @Bean
  AuthenticationEntryPoint authenticationEntryPoint() {
    return (request, response, authException) ->
        ApiErrorWriter.write(
            response,
            HttpStatus.UNAUTHORIZED,
            "UNAUTHENTICATED",
            "Authentication is required to access this resource.");
  }

  /** Writes the uniform {@code ErrorResponse} 403 for authorization failures (issue #45). */
  @Bean
  AccessDeniedHandler accessDeniedHandler() {
    return (request, response, accessDeniedException) ->
        ApiErrorWriter.write(
            response,
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "You do not have permission to access this resource.");
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(QnopProperties properties) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(properties.cors().allowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    config.setMaxAge(3_600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
