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
import io.qnop.api.v1.model.LoginRequest;
import io.qnop.api.v1.model.TokenResponse;
import io.qnop.security.QnopProperties;
import io.qnop.service.AuthService;
import io.qnop.service.AuthService.ChangePasswordOutcome;
import io.qnop.service.JwtTokenService;
import io.qnop.service.RefreshTokenService;
import io.qnop.service.RefreshTokenService.IssuedRefreshToken;
import io.qnop.service.RefreshTokenService.RotationResult;
import io.qnop.service.TokenRevocationService;
import io.qnop.web.security.RefreshTokenCookieFactory;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
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

  private static final String TOKEN_TYPE = "Bearer";

  private final AuthService authService;
  private final JwtTokenService jwtTokenService;
  private final RefreshTokenService refreshTokenService;
  private final TokenRevocationService tokenRevocationService;
  private final RefreshTokenCookieFactory cookieFactory;
  private final QnopProperties properties;

  public AuthController(
      AuthService authService,
      JwtTokenService jwtTokenService,
      RefreshTokenService refreshTokenService,
      TokenRevocationService tokenRevocationService,
      RefreshTokenCookieFactory cookieFactory,
      QnopProperties properties) {
    this.authService = authService;
    this.jwtTokenService = jwtTokenService;
    this.refreshTokenService = refreshTokenService;
    this.tokenRevocationService = tokenRevocationService;
    this.cookieFactory = cookieFactory;
    this.properties = properties;
  }

  @Override
  public ResponseEntity<TokenResponse> login(LoginRequest request) {
    UUID userId =
        authService
            .authenticate(request.getUsernameOrEmail(), request.getPassword())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
    return issueSession(userId);
  }

  @Override
  public ResponseEntity<TokenResponse> refresh(String refreshCookie) {
    if (refreshCookie == null || refreshCookie.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing refresh token");
    }
    RotationResult result = refreshTokenService.rotate(refreshCookie);
    if (result instanceof RotationResult.Success success) {
      return issueSession(success.userId(), success.token());
    }
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
      case NOT_LOCAL ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "Password change is not available for this account");
      case WRONG_PASSWORD, USER_NOT_FOUND ->
          throw new ResponseStatusException(
              HttpStatus.UNAUTHORIZED, "Current password is incorrect");
    };
  }

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
    if (jwt.getId() != null && jwt.getSubject() != null && jwt.getExpiresAt() != null) {
      tokenRevocationService.revokeToken(
          jwt.getId(), UUID.fromString(jwt.getSubject()), jwt.getExpiresAt());
    }
  }

  private UUID requireAuthenticatedUserId() {
    return currentAccessToken()
        .map(Jwt::getSubject)
        .map(UUID::fromString)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
  }

  private java.util.Optional<Jwt> currentAccessToken() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      return java.util.Optional.of(jwt);
    }
    return java.util.Optional.empty();
  }
}
