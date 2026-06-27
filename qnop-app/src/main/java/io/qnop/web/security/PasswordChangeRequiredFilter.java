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

import io.qnop.service.UserService;
import io.qnop.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces a password change before any non-auth resource is reachable (issue #20). When the
 * authenticated JWT subject is a user with {@code password_change_required = true}, every request
 * outside {@code /api/v1/auth/} is rejected with {@code 403}, so the only thing such a user can do
 * is change their password (and refresh/logout). Runs after the resource-server authentication has
 * populated the {@link JwtAuthenticationToken}.
 */
@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

  private static final String AUTH_PREFIX = "/api/v1/auth/";

  private final UserService userService;

  public PasswordChangeRequiredFilter(UserService userService) {
    this.userService = userService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwt
        && !request.getRequestURI().startsWith(AUTH_PREFIX)
        && requiresPasswordChange(jwt.getName())) {
      ApiErrorWriter.write(
          response,
          HttpStatus.FORBIDDEN,
          "PASSWORD_CHANGE_REQUIRED",
          "You must change your password before accessing this resource.");
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * The JWT subject is the {@code qnop_user.id} (issue #17). A non-UUID subject can only come from
   * a forged/legacy token; treat it as "no change required" so the auth filters reject it on the
   * correct path rather than bleeding through as a 403.
   */
  private boolean requiresPasswordChange(String subject) {
    final UUID userId;
    try {
      userId = UUID.fromString(subject);
    } catch (IllegalArgumentException e) {
      return false;
    }
    return userService.passwordChangeRequired(userId);
  }
}
