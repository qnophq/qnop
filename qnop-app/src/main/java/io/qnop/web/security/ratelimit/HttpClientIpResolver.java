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
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * Resolves the client IP for rate-limit keying (issue #18, ADR-0027), gating {@code
 * X-Forwarded-For} behind a configurable trusted-proxy allow-list so the header cannot be spoofed
 * to rotate per-IP buckets.
 *
 * <p>Resolution rules:
 *
 * <ol>
 *   <li>No / blank {@code X-Forwarded-For} → {@code request.getRemoteAddr()}.
 *   <li>Empty trusted-proxy list → no proxy is trusted; ignore the header and use {@code
 *       getRemoteAddr()}. This is the secure default for a server with no reverse proxy: otherwise
 *       an attacker spoofs the header to cycle buckets at will.
 *   <li>The immediate hop ({@code getRemoteAddr()}) is not in the trust list → the header is
 *       attacker-controlled; use {@code getRemoteAddr()}.
 *   <li>Otherwise the immediate hop is a trusted proxy → return the leftmost {@code
 *       X-Forwarded-For} entry (the original client). Single-hop trust; multi-hop chains would need
 *       a right-to-left walk.
 * </ol>
 *
 * <p>Operators behind a reverse proxy MUST set {@code qnop.auth.rate-limit.trusted-proxy-cidrs} to
 * the proxy's egress IPs, or every client appears to come from the proxy and per-IP limiting
 * collapses to a single shared bucket.
 */
@Component
public class HttpClientIpResolver {

  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  private final List<IpAddressMatcher> trustedProxyMatchers;

  public HttpClientIpResolver(RateLimitProperties properties) {
    this.trustedProxyMatchers =
        properties.trustedProxyCidrs().stream()
            .map(String::trim)
            .map(IpAddressMatcher::new)
            .toList();
  }

  /** Returns the best-trusted client IP for the request. */
  public String resolve(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    String forwarded = request.getHeader(X_FORWARDED_FOR);
    if (forwarded == null || forwarded.isBlank()) {
      return remoteAddr;
    }
    if (trustedProxyMatchers.isEmpty()) {
      return remoteAddr;
    }
    if (trustedProxyMatchers.stream().noneMatch(matcher -> matcher.matches(remoteAddr))) {
      return remoteAddr;
    }
    return forwarded.split(",")[0].trim();
  }
}
