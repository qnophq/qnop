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

/**
 * Builds {@link RateLimitProperties} for tests without spelling out every scope positionally, so a
 * new scope does not ripple through every test that only cares about one setting.
 */
public final class RateLimitPropertiesFixture {

  private RateLimitPropertiesFixture() {}

  /** All scopes at their defaults, no trusted proxy. */
  public static RateLimitProperties defaults() {
    return withTrustedProxies(List.of());
  }

  /** All scopes at their defaults, with the given trusted-proxy CIDRs. */
  public static RateLimitProperties withTrustedProxies(List<String> trustedProxyCidrs) {
    return new RateLimitProperties(
        trustedProxyCidrs,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        100_000L);
  }
}
