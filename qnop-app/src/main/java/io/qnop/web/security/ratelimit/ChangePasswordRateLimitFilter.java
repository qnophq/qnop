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
package io.qnop.web.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Rate-limits {@code POST /api/v1/auth/change-password} per authenticated subject (issue #18),
 * stopping online brute-force against the current password while tolerating legitimate
 * password-rotation retries (default 5/300s).
 *
 * <p>Keyed by the JWT {@code sub} from the security context, so it must run after the
 * resource-server bearer authentication populates it. An unauthenticated request has no subject
 * ({@code null} key) and passes through to the normal {@code 401}.
 */
@Component
public class ChangePasswordRateLimitFilter extends AbstractRateLimitFilter {

  static final String PATH = "/api/v1/auth/change-password";

  private final RateLimitProperties.Limit limit;

  public ChangePasswordRateLimitFilter(
      BucketRateLimitService rateLimitService, RateLimitProperties properties) {
    super(rateLimitService, "Too many password-change attempts. Please try again later.");
    this.limit = properties.changePassword();
  }

  @Override
  protected boolean handles(HttpServletRequest request) {
    return "POST".equalsIgnoreCase(request.getMethod()) && PATH.equals(request.getRequestURI());
  }

  @Override
  protected String resolveKey(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      return jwt.getSubject();
    }
    return null;
  }

  @Override
  protected String scope() {
    return "change-password";
  }

  @Override
  protected int maxAttempts() {
    return limit.maxAttempts();
  }

  @Override
  protected long windowSeconds() {
    return limit.windowSeconds();
  }
}
