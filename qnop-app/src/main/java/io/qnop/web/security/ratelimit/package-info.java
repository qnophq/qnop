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

/**
 * Bucket4j-backed rate limiting for the public/auth endpoints (issue #18, ADR-0027).
 *
 * <p>A reusable {@link io.qnop.web.security.ratelimit.BucketRateLimitService} (Bucket4j + Caffeine)
 * is fronted by {@code OncePerRequestFilter}s that return {@code 429} + {@code Retry-After} when a
 * scope's bucket is drained. Client IPs are resolved through {@link
 * io.qnop.web.security.ratelimit.HttpClientIpResolver}, which only honours {@code X-Forwarded-For}
 * from configured trusted proxies. Limits are tunable via {@code qnop.auth.rate-limit.*} ({@link
 * io.qnop.web.security.ratelimit.RateLimitProperties}).
 */
package io.qnop.web.security.ratelimit;
