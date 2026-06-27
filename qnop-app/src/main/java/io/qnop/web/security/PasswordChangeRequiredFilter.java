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

import io.qnop.service.JwtTokenService;
import io.qnop.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces a password change before any non-auth resource is reachable (issue #20). When the
 * authenticated access token carries {@code pcr = true} (the user's {@code
 * password_change_required} flag, baked in at mint time — issue #167), every request outside {@code
 * /api/v1/auth/} is rejected with a {@code 403} {@code ErrorResponse}, so the only thing such a
 * user can do is change their password (and refresh/logout). Reading the flag from the verified
 * token avoids a per-request {@code qnop_user} lookup on the hot path; it stays correct because
 * every transition that flips the flag also revokes the user's live tokens, forcing a fresh mint
 * that reflects the new value. Runs after the resource-server authentication has populated the
 * {@link JwtAuthenticationToken}.
 */
@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

  private static final String AUTH_PREFIX = "/api/v1/auth/";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwt
        && !request.getRequestURI().startsWith(AUTH_PREFIX)
        && Boolean.TRUE.equals(jwt.getToken().getClaimAsBoolean(JwtTokenService.PCR_CLAIM))) {
      ApiErrorWriter.write(
          response,
          HttpStatus.FORBIDDEN,
          "PASSWORD_CHANGE_REQUIRED",
          "You must change your password before accessing this resource.");
      return;
    }
    filterChain.doFilter(request, response);
  }
}
