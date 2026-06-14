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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Base for the per-endpoint rate-limit filters (issue #18). A subclass declares which requests it
 * {@linkplain #handles(HttpServletRequest) handles}, how to {@linkplain
 * #resolveKey(HttpServletRequest) key} the bucket, and the {@linkplain #scope() scope}/limit; this
 * base consumes a token and, on rejection, short-circuits the chain with {@code 429} + {@code
 * Retry-After} and a JSON body matching the API's bare error shape.
 *
 * <p>A {@code null} key means the request cannot be keyed (e.g. an unauthenticated change-password
 * request with no subject) — the filter passes it through untouched so the downstream chain returns
 * its normal {@code 401}, rather than masking it behind a rate-limit decision.
 */
abstract class AbstractRateLimitFilter extends OncePerRequestFilter {

  private final BucketRateLimitService rateLimitService;
  private final String message;

  protected AbstractRateLimitFilter(BucketRateLimitService rateLimitService, String message) {
    this.rateLimitService = rateLimitService;
    this.message = message;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !handles(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String key = resolveKey(request);
    if (key == null) {
      filterChain.doFilter(request, response);
      return;
    }
    RateLimitResult result =
        rateLimitService.tryConsume(scope(), key, maxAttempts(), windowSeconds());
    if (result instanceof RateLimitResult.Rejected rejected) {
      writeTooManyRequests(response, rejected.retryAfterSeconds());
      return;
    }
    filterChain.doFilter(request, response);
  }

  private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds)
      throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write(
            "{\"error\":\"too_many_requests\",\"message\":\""
                + message
                + "\",\"retryAfterSeconds\":"
                + retryAfterSeconds
                + "}");
  }

  /** Whether this filter applies to the request (method + path match). */
  protected abstract boolean handles(HttpServletRequest request);

  /** The bucket key for the request, or {@code null} to pass through without a check. */
  protected abstract String resolveKey(HttpServletRequest request);

  /** Stable scope label namespacing this filter's buckets. */
  protected abstract String scope();

  protected abstract int maxAttempts();

  protected abstract long windowSeconds();
}
