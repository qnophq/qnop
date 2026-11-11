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
 * refill window ({@code windowSeconds}). All values are optional — absent entries fall back to the
 * per-scope defaults below.
 *
 * <p>Two kinds of scope exist (ADR-0027, amended by #899): <b>per-IP</b> scopes enforced in servlet
 * filters before the request reaches a controller, and <b>per-identifier</b> scopes (account,
 * e-mail address, reset token) enforced in the controller once the body is parsed. Identifier
 * buckets are keyed on a hash of the normalized identifier, never the identifier itself.
 *
 * @param trustedProxyCidrs CIDR ranges whose {@code X-Forwarded-For} entries are trusted by {@link
 *     HttpClientIpResolver}. Empty by default — the secure default for a server with no reverse
 *     proxy, which then ignores {@code X-Forwarded-For} entirely. Operators behind a proxy MUST set
 *     this to the proxy's egress IPs, or per-IP limits collapse to one shared bucket.
 * @param login IP-keyed limit for {@code POST /api/v1/auth/login} (default 10 / 60s)
 * @param refresh IP-keyed limit for {@code POST /api/v1/auth/refresh} (default 30 / 60s)
 * @param changePassword subject-keyed limit for {@code POST /api/v1/auth/change-password} (default
 *     5 / 300s)
 * @param register IP-keyed limit for {@code POST /api/v1/auth/register} (default 5 / 3600s)
 * @param forgotPassword IP-keyed limit for {@code POST /api/v1/auth/forgot-password} (default 5 /
 *     3600s)
 * @param resetPassword IP-keyed limit for {@code POST /api/v1/auth/reset-password} (default 5 /
 *     900s)
 * @param loginAccount per-account limit on <em>failed</em> logins for one username / e-mail
 *     (default 10 / 900s); a successful login gives its token back
 * @param registerEmail per-e-mail limit for registrations (default 5 / 3600s)
 * @param forgotPasswordEmail per-e-mail limit for reset requests (default 5 / 3600s)
 * @param resetPasswordToken per-token limit for reset attempts (default 10 / 3600s)
 * @param maxBuckets upper bound on the number of live token buckets held in memory (default
 *     100&nbsp;000). Bounds memory under a high-cardinality key space — e.g. a spoofable client IP
 *     when no trusted proxy is configured (issue #49).
 */
@ConfigurationProperties(prefix = "qnop.auth.rate-limit")
public record RateLimitProperties(
    List<String> trustedProxyCidrs,
    Limit login,
    Limit refresh,
    Limit changePassword,
    Limit register,
    Limit forgotPassword,
    Limit resetPassword,
    Limit loginAccount,
    Limit registerEmail,
    Limit forgotPasswordEmail,
    Limit resetPasswordToken,
    @DefaultValue("100000") long maxBuckets) {

  // A whole scope absent from config arrives as null here; substitute its per-scope default. The
  // Limit components themselves carry @DefaultValue, so a partially-specified scope keeps the
  // unset field at the catalogue default rather than 0.
  public RateLimitProperties {
    trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
    login = login != null ? login : new Limit(10, 60);
    refresh = refresh != null ? refresh : new Limit(30, 60);
    changePassword = changePassword != null ? changePassword : new Limit(5, 300);
    register = register != null ? register : new Limit(5, 3600);
    forgotPassword = forgotPassword != null ? forgotPassword : new Limit(5, 3600);
    resetPassword = resetPassword != null ? resetPassword : new Limit(5, 900);
    loginAccount = loginAccount != null ? loginAccount : new Limit(10, 900);
    registerEmail = registerEmail != null ? registerEmail : new Limit(5, 3600);
    forgotPasswordEmail = forgotPasswordEmail != null ? forgotPasswordEmail : new Limit(5, 3600);
    resetPasswordToken = resetPasswordToken != null ? resetPasswordToken : new Limit(10, 3600);
  }

  /**
   * A single bucket configuration: {@code maxAttempts} tokens, greedily refilled over {@code
   * windowSeconds}.
   *
   * @param maxAttempts requests allowed from one key before its bucket is empty and further
   *     requests are rejected with 429 — the burst capacity
   * @param windowSeconds length in seconds of the rolling window over which the bucket refills back
   *     to {@code maxAttempts}
   */
  public record Limit(
      @DefaultValue("10") int maxAttempts, @DefaultValue("60") long windowSeconds) {}
}
