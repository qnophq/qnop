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
 * Rate-limits {@code POST /api/v1/auth/verify-email} per client IP (issue #900). Each request with
 * a usable token costs one password-hash comparison, so an unthrottled endpoint would be a cheap
 * way to burn server CPU; the per-token attempt cap in the service bounds guessing on one link,
 * this bounds the volume from one source. Configured under {@code
 * qnop.auth.rate-limit.verify-email.*} (default 10 per 15 minutes) without a {@link
 * RateLimitProperties} component, so the limiter can later be folded into that catalogue without a
 * breaking change.
 */
@Component
public class VerifyEmailRateLimitFilter extends AbstractRateLimitFilter {

  static final String PATH = "/api/v1/auth/verify-email";

  private final HttpClientIpResolver clientIpResolver;
  private final int maxAttempts;
  private final long windowSeconds;

  public VerifyEmailRateLimitFilter(
      BucketRateLimitService rateLimitService,
      HttpClientIpResolver clientIpResolver,
      @Value("${qnop.auth.rate-limit.verify-email.max-attempts:10}") int maxAttempts,
      @Value("${qnop.auth.rate-limit.verify-email.window-seconds:900}") long windowSeconds) {
    super(rateLimitService, "Too many verification attempts. Please try again later.");
    this.clientIpResolver = clientIpResolver;
    this.maxAttempts = maxAttempts;
    this.windowSeconds = windowSeconds;
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
    return "verify-email";
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
