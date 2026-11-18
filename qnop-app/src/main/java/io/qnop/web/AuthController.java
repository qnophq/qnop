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

import io.qnop.api.v1.endpoint.AuthApi;
import io.qnop.api.v1.model.ChangePasswordRequest;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.LoginRequest;
import io.qnop.api.v1.model.TokenResponse;
import io.qnop.observability.metrics.AuthMeters;
import io.qnop.observability.metrics.AuthMeters.LoginOutcome;
import io.qnop.observability.metrics.AuthMeters.Method;
import io.qnop.observability.metrics.AuthMeters.RefreshOutcome;
import io.qnop.security.QnopProperties;
import io.qnop.service.AuthService;
import io.qnop.service.AuthService.ChangePasswordOutcome;
import io.qnop.service.JwtTokenService;
import io.qnop.service.RefreshTokenService;
import io.qnop.service.RefreshTokenService.IssuedRefreshToken;
import io.qnop.service.RefreshTokenService.RotationResult;
import io.qnop.service.TokenRevocationService;
import io.qnop.web.security.RefreshTokenCookieFactory;
import io.qnop.web.security.ratelimit.AuthIdentifierRateLimiter;
import io.qnop.web.security.ratelimit.AuthIdentifierRateLimiter.LoginAttempt;
import io.qnop.web.security.ratelimit.LoginDeviceCookies;
import io.qnop.web.security.ratelimit.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authentication endpoints (issue #17): local login, refresh-token rotation, logout, and password
 * change. Implements the generated {@link AuthApi} contract. Access tokens are returned in the
 * body; the rotating refresh token is delivered as an HttpOnly {@code qnop_refresh} cookie. Bad
 * credentials and invalid/replayed refresh tokens return {@code 401} without leaking which check
 * failed.
 */
@RestController
public class AuthController implements AuthApi {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private static final String TOKEN_TYPE = "Bearer";

  private final AuthService authService;
  private final JwtTokenService jwtTokenService;
  private final RefreshTokenService refreshTokenService;
  private final TokenRevocationService tokenRevocationService;
  private final RefreshTokenCookieFactory cookieFactory;
  private final QnopProperties properties;
  private final AuthIdentifierRateLimiter identifierRateLimiter;
  private final HttpServletRequest httpRequest;
  private final LoginDeviceCookies deviceCookies;
  private final AuthMeters meters;

  public AuthController(
      AuthService authService,
      JwtTokenService jwtTokenService,
      RefreshTokenService refreshTokenService,
      TokenRevocationService tokenRevocationService,
      RefreshTokenCookieFactory cookieFactory,
      QnopProperties properties,
      AuthIdentifierRateLimiter identifierRateLimiter,
      HttpServletRequest httpRequest,
      LoginDeviceCookies deviceCookies,
      AuthMeters meters) {
    this.authService = authService;
    this.jwtTokenService = jwtTokenService;
    this.refreshTokenService = refreshTokenService;
    this.tokenRevocationService = tokenRevocationService;
    this.cookieFactory = cookieFactory;
    this.properties = properties;
    this.identifierRateLimiter = identifierRateLimiter;
    // A request-scoped proxy: resolves to the request being served on each call.
    this.httpRequest = httpRequest;
    this.deviceCookies = deviceCookies;
    this.meters = meters;
  }

  @Override
  public ResponseEntity<TokenResponse> login(LoginRequest request) {
    // Never the identifier that was tried (issue #659): it is an e-mail address
    // or a username, and a failed login writes it into a file with no access
    // control. The request id ties the attempt to its rate-limit and audit rows.
    //
    // Per-account throttle (issue #899): checked before the credentials, for known and unknown
    // identifiers alike, and only failures count against it. The credentials are checked even when
    // the account's ceiling refused the attempt, so a silent refusal takes as long as a real one.
    AtomicReference<LoginAttempt> attempt = new AtomicReference<>();
    Optional<UUID> authenticated;
    try {
      authenticated =
          authService.authenticate(
              request.getUsernameOrEmail(),
              request.getPassword(),
              account -> {
                attempt.set(
                    identifierRateLimiter.beginLogin(
                        request.getUsernameOrEmail(), account, httpRequest));
                return !attempt.get().refused();
              });
    } catch (RateLimitExceededException throttled) {
      meters.login(Method.LOCAL, LoginOutcome.THROTTLED);
      throw throttled;
    } catch (RuntimeException failure) {
      meters.login(Method.LOCAL, LoginOutcome.ERROR);
      throw failure;
    }
    if (authenticated.isEmpty()) {
      // The client is told the same either way; the meter tells the operator which it was.
      boolean refused = attempt.get() != null && attempt.get().refused();
      meters.login(Method.LOCAL, refused ? LoginOutcome.THROTTLED : LoginOutcome.REJECTED);
      log.info("Login rejected: invalid credentials");
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
    UUID userId = authenticated.get();
    identifierRateLimiter.loginSucceeded(attempt.get());
    log.info("Login succeeded for {}", userId);
    ResponseEntity<TokenResponse> session;
    try {
      session = issueSession(userId);
    } catch (RuntimeException failure) {
      // Right credentials, no session: not a success the user would recognise.
      meters.login(Method.LOCAL, LoginOutcome.ERROR);
      throw failure;
    }
    meters.login(Method.LOCAL, LoginOutcome.SUCCESS);
    // Recognise this device on the next attempt (issue #899, LoginDeviceCookies).
    return ResponseEntity.status(session.getStatusCode())
        .headers(session.getHeaders())
        .header(HttpHeaders.SET_COOKIE, deviceCookies.issue(userId).toString())
        .body(session.getBody());
  }

  @Override
  public ResponseEntity<TokenResponse> refresh(String refreshCookie) {
    if (refreshCookie == null || refreshCookie.isBlank()) {
      meters.refresh(RefreshOutcome.MISSING);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing refresh token");
    }
    RotationResult result = refreshTokenService.rotate(refreshCookie);
    meters.refresh(
        switch (result) {
          case RotationResult.Success rotated -> RefreshOutcome.SUCCESS;
          case RotationResult.Reused reused -> RefreshOutcome.REUSED;
          case RotationResult.Unknown unknown -> RefreshOutcome.UNKNOWN;
        });
    if (result instanceof RotationResult.Success success) {
      log.debug("Refresh token rotated for {}", success.userId());
      return issueSession(success.userId(), success.token());
    }
    // A presented token that is not valid is either replayed or forged, and both
    // are worth seeing: a burst of them is the shape of a stolen cookie
    // (ADR-0026). WARN, because unlike a mistyped password this should not happen
    // in normal use.
    log.warn("Refresh rejected: the presented token was reused or unknown");
    // Reused or Unknown: clear the cookie and reject. The two are indistinguishable to the client.
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.SET_COOKIE, cookieFactory.clear().toString())
        .build();
  }

  @Override
  public ResponseEntity<Void> logout(String refreshCookie) {
    if (refreshCookie != null && !refreshCookie.isBlank()) {
      refreshTokenService.revokePresentedFamily(refreshCookie);
    }
    currentAccessToken().ifPresent(this::revokeAccessToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookieFactory.clear().toString())
        .build();
  }

  @Override
  public ResponseEntity<Void> changePassword(ChangePasswordRequest request) {
    UUID userId = requireAuthenticatedUserId();
    ChangePasswordOutcome outcome =
        authService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
    return switch (outcome) {
      case SUCCESS -> ResponseEntity.noContent().build();
      case NOT_LOCAL -> throw new ExternalAccountException();
      case WRONG_PASSWORD, USER_NOT_FOUND ->
          throw new ResponseStatusException(
              HttpStatus.UNAUTHORIZED, "Current password is incorrect");
    };
  }

  /**
   * A local password change was attempted on an account that authenticates via an external provider
   * (OIDC). Mapped to 409 with code {@code EXTERNAL_ACCOUNT}, consistent with the admin endpoints'
   * rejection of OIDC accounts (issue #185).
   */
  @ExceptionHandler(ExternalAccountException.class)
  public ResponseEntity<ErrorResponse> onExternalAccount(ExternalAccountException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ErrorResponse()
                .code("EXTERNAL_ACCOUNT")
                .message(
                    "Password change is not available for an account that authenticates via an"
                        + " external provider.")
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC)));
  }

  private static final class ExternalAccountException extends RuntimeException {}

  private ResponseEntity<TokenResponse> issueSession(UUID userId) {
    return issueSession(userId, refreshTokenService.issue(userId));
  }

  private ResponseEntity<TokenResponse> issueSession(UUID userId, IssuedRefreshToken refreshToken) {
    String accessToken = jwtTokenService.issueAccessToken(userId);
    ResponseCookie cookie = cookieFactory.build(refreshToken.plaintext(), refreshToken.maxAge());
    TokenResponse body =
        new TokenResponse()
            .accessToken(accessToken)
            .tokenType(TOKEN_TYPE)
            .expiresInSeconds(properties.auth().accessTokenTtl().toSeconds());
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
  }

  private void revokeAccessToken(Jwt jwt) {
    UUID userId = asUserId(jwt.getSubject());
    // A non-UUID subject is a machine principal (issue #686): nothing of it lives in the
    // user-keyed revocation store, so there is nothing to revoke here.
    if (jwt.getId() != null && userId != null && jwt.getExpiresAt() != null) {
      tokenRevocationService.revokeToken(jwt.getId(), userId, jwt.getExpiresAt());
    }
  }

  private UUID requireAuthenticatedUserId() {
    return currentAccessToken()
        .map(Jwt::getSubject)
        .map(AuthController::asUserId)
        .filter(java.util.Objects::nonNull)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
  }

  /**
   * The subject as a user id, or null for a machine principal's non-UUID subject (#686). Note the
   * deliberate divergence from {@link CurrentUser#requireUserId()}: there a machine principal is a
   * <em>forbidden</em> actor (403) on a user resource; here on the auth surface it is simply not an
   * authenticated user session (401) — /auth/me has nothing to say about it.
   */
  private static UUID asUserId(String subject) {
    if (subject == null) {
      return null;
    }
    try {
      return UUID.fromString(subject);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private java.util.Optional<Jwt> currentAccessToken() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      return java.util.Optional.of(jwt);
    }
    return java.util.Optional.empty();
  }
}
