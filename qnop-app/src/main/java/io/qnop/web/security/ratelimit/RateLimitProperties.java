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

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable limits for the auth rate-limiting subsystem (issue #18, ADR-0027), bound from {@code
 * qnop.auth.rate-limit.*}. Each scope carries its own bucket capacity ({@code maxAttempts}) and
 * refill window ({@code windowSeconds}); defaults match the issue. All values are optional — absent
 * entries fall back to the per-component {@link DefaultValue}s.
 *
 * <p>The register and password-reset endpoints (and their email-/token-keyed buckets) arrive with
 * issue #20; their limits are added here when those endpoints land. This module wires the limits
 * for the endpoints that exist today: login, refresh, and change-password.
 *
 * @param trustedProxyCidrs CIDR ranges whose {@code X-Forwarded-For} header is trusted by {@link
 *     HttpClientIpResolver}. Empty by default — the secure default for a server with no reverse
 *     proxy, which then ignores {@code X-Forwarded-For} entirely. Operators behind a proxy MUST set
 *     this to the proxy's egress IPs, or per-IP limits collapse to one shared bucket.
 * @param login IP-keyed limit for {@code POST /api/v1/auth/login} (default 10 / 60s)
 * @param refresh IP-keyed limit for {@code POST /api/v1/auth/refresh} (default 30 / 60s)
 * @param changePassword subject-keyed limit for {@code POST /api/v1/auth/change-password} (default
 *     5 / 300s)
 */
@ConfigurationProperties(prefix = "qnop.auth.rate-limit")
public record RateLimitProperties(
    List<String> trustedProxyCidrs, Limit login, Limit refresh, Limit changePassword) {

  // A whole scope absent from config arrives as null here; substitute its per-scope default. The
  // Limit components themselves carry @DefaultValue, so a partially-specified scope keeps the
  // unset field at the catalogue default rather than 0.
  public RateLimitProperties {
    trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
    login = login != null ? login : new Limit(10, 60);
    refresh = refresh != null ? refresh : new Limit(30, 60);
    changePassword = changePassword != null ? changePassword : new Limit(5, 300);
  }

  /**
   * A single bucket configuration: {@code maxAttempts} tokens, greedily refilled over {@code
   * windowSeconds}.
   */
  public record Limit(
      @DefaultValue("10") int maxAttempts, @DefaultValue("60") long windowSeconds) {}
}
