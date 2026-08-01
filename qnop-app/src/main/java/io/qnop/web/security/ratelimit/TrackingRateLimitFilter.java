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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rate-limits the measurement endpoint per client IP (issue #666).
 *
 * <p>It is unauthenticated by necessity — the sign-in screen is measured too — and it forwards to a
 * backend the operator pays for, so an unbounded one is an invitation to flood somebody else's
 * analytics with nonsense. The ceiling is generous: a busy reviewer produces a measurement per
 * navigation, not per second.
 *
 * <p>Its own {@code qnop.tracking.*} keys rather than a sixth scope under {@code
 * qnop.auth.rate-limit}: this has nothing to do with authentication, and a limit filed under "auth"
 * is a limit nobody finds when they go looking for it.
 */
@Component
public class TrackingRateLimitFilter extends AbstractRateLimitFilter {

  static final String PATH_PREFIX = "/t/c";

  private final HttpClientIpResolver clientIpResolver;
  private final int maxAttempts;
  private final long windowSeconds;

  public TrackingRateLimitFilter(
      BucketRateLimitService rateLimitService,
      HttpClientIpResolver clientIpResolver,
      @Value("${qnop.tracking.rate-limit.max-attempts:120}") int maxAttempts,
      @Value("${qnop.tracking.rate-limit.window-seconds:60}") long windowSeconds) {
    super(rateLimitService, "Too many measurements. Please try again later.");
    this.clientIpResolver = clientIpResolver;
    this.maxAttempts = maxAttempts;
    this.windowSeconds = windowSeconds;
  }

  @Override
  protected boolean handles(HttpServletRequest request) {
    // GET as well as POST: Matomo and Pirsch measure over the query string, and a
    // limit that only counted POSTs would leave those two unbounded.
    return request.getRequestURI().startsWith(PATH_PREFIX)
        && ("POST".equalsIgnoreCase(request.getMethod())
            || "GET".equalsIgnoreCase(request.getMethod()));
  }

  @Override
  protected String resolveKey(HttpServletRequest request) {
    return clientIpResolver.resolve(request);
  }

  @Override
  protected String scope() {
    return "tracking";
  }

  @Override
  protected int maxAttempts() {
    return maxAttempts;
  }

  @Override
  protected long windowSeconds() {
    return windowSeconds;
  }
}
