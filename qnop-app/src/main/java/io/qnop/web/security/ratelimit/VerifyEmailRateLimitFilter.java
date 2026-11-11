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
 * Rate-limits {@code POST /api/v1/auth/verify-email} and its {@code /registration} lookup per
 * client network (issue #900), in one bucket. A verification costs a password-hash comparison (and,
 * when new credentials are set, a hash computation), so an unthrottled endpoint would be a cheap
 * way to burn server CPU. Limit {@code qnop.auth.rate-limit.verify-email.*} (default 10 / 900s).
 */
@Component
public class VerifyEmailRateLimitFilter extends AbstractRateLimitFilter {

  static final String PATH = "/api/v1/auth/verify-email";
  static final String REGISTRATION_PATH = "/api/v1/auth/verify-email/registration";

  private final HttpClientIpResolver clientIpResolver;
  private final RateLimitProperties.Limit limit;

  public VerifyEmailRateLimitFilter(
      BucketRateLimitService rateLimitService,
      HttpClientIpResolver clientIpResolver,
      RateLimitProperties properties) {
    super(rateLimitService, "Too many verification attempts. Please try again later.");
    this.clientIpResolver = clientIpResolver;
    this.limit = properties.verifyEmail();
  }

  @Override
  protected boolean handles(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return "POST".equalsIgnoreCase(request.getMethod())
        && (PATH.equals(uri) || REGISTRATION_PATH.equals(uri));
  }

  @Override
  protected String resolveKey(HttpServletRequest request) {
    // IPv6 grouped by /56 (ClientNetworks), like the other auth limiters.
    return ClientNetworks.perIpKey(clientIpResolver.resolve(request));
  }

  @Override
  protected String scope() {
    return "verify-email";
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
