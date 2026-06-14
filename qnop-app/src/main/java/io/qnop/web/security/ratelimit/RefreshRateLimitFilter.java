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
import org.springframework.stereotype.Component;

/**
 * Rate-limits {@code POST /api/v1/auth/refresh} per client IP (issue #18), bounding refresh-token
 * spam / DoS. The limit is generous (default 30/60s) so an active multi-tab session refreshing
 * normally is never throttled.
 */
@Component
public class RefreshRateLimitFilter extends AbstractRateLimitFilter {

  static final String PATH = "/api/v1/auth/refresh";

  private final HttpClientIpResolver clientIpResolver;
  private final RateLimitProperties.Limit limit;

  public RefreshRateLimitFilter(
      BucketRateLimitService rateLimitService,
      HttpClientIpResolver clientIpResolver,
      RateLimitProperties properties) {
    super(rateLimitService, "Too many refresh attempts. Please try again later.");
    this.clientIpResolver = clientIpResolver;
    this.limit = properties.refresh();
  }

  @Override
  protected boolean handles(HttpServletRequest request) {
    return "POST".equalsIgnoreCase(request.getMethod()) && PATH.equals(request.getRequestURI());
  }

  @Override
  protected String resolveKey(HttpServletRequest request) {
    return clientIpResolver.resolve(request);
  }

  @Override
  protected String scope() {
    return "refresh";
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
