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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.service.UserService;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit tests for {@link PasswordChangeRequiredFilter}. Verifies the gate blocks protected paths for
 * a user that must change their password and — the focus of issue #165 — that the 403 body is the
 * uniform {@code ErrorResponse} envelope ({@code code}/{@code message}/{@code timestamp}) written
 * by {@link io.qnop.web.ApiErrorWriter}, not the legacy ad-hoc {@code error} field.
 */
@ExtendWith(MockitoExtension.class)
class PasswordChangeRequiredFilterTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Mock private UserService userService;
  @Mock private FilterChain filterChain;

  private PasswordChangeRequiredFilter newFilter() {
    return new PasswordChangeRequiredFilter(userService);
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName(
      "blocks a protected path with the uniform ErrorResponse envelope when a change is required")
  void blocksWithUniformEnvelope() throws Exception {
    authenticateAs(USER_ID);
    when(userService.passwordChangeRequired(USER_ID)).thenReturn(true);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    MockHttpServletResponse response = new MockHttpServletResponse();

    newFilter().doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
    assertThat(response.getContentAsString())
        .contains("\"code\":\"PASSWORD_CHANGE_REQUIRED\"")
        .contains("\"message\":")
        .contains("\"timestamp\":")
        .doesNotContain("\"error\":");
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  @DisplayName("never blocks an /api/v1/auth/ path so the user can change the password")
  void allowsAuthPaths() throws Exception {
    authenticateAs(USER_ID);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/auth/change-password");
    MockHttpServletResponse response = new MockHttpServletResponse();

    newFilter().doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
  }

  @Test
  @DisplayName("lets the request through when no password change is required")
  void allowsWhenNoChangeRequired() throws Exception {
    authenticateAs(USER_ID);
    when(userService.passwordChangeRequired(USER_ID)).thenReturn(false);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    MockHttpServletResponse response = new MockHttpServletResponse();

    newFilter().doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
  }

  private static void authenticateAs(UUID subject) {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(subject.toString())
            .issuedAt(Instant.EPOCH)
            .expiresAt(Instant.EPOCH.plusSeconds(900))
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }
}
