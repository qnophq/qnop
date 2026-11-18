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
package io.qnop.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters of the sign-in surface: logins by method and outcome, refresh-token rotations by
 * outcome, and requests a rate limit turned away.
 *
 * <p>None of them says <em>who</em>: no account, no address, no provider name (ADR-0065). The
 * question they answer is whether sign-in works and whether somebody is leaning on it.
 */
@Component
public class AuthMeters {

  public static final String LOGIN = "qnop.auth.login";
  public static final String REFRESH = "qnop.auth.refresh";
  public static final String RATE_LIMITED = "qnop.ratelimit.rejected";

  /** How the user proved who they are. */
  public enum Method {
    LOCAL,
    OIDC
  }

  /** How a login attempt ended. */
  public enum LoginOutcome {
    SUCCESS,
    /** Wrong credentials, or an account that may not sign in. */
    REJECTED,
    /** A per-account limit refused the attempt before the credentials counted. */
    THROTTLED,
    /** The login could not be completed for a reason that is not the user's. */
    ERROR
  }

  /** How a refresh-token rotation ended. */
  public enum RefreshOutcome {
    SUCCESS,
    /** No refresh cookie was presented. */
    MISSING,
    /** An already-rotated token was presented again: the shape of a stolen cookie (ADR-0026). */
    REUSED,
    /** The token is not one this server issued, or has expired. */
    UNKNOWN
  }

  private final MeterRegistry registry;

  public AuthMeters(MeterRegistry registry) {
    this.registry = registry;
  }

  public void login(Method method, LoginOutcome outcome) {
    Counter.builder(LOGIN)
        .description("Login attempts")
        .tag("method", MeterTags.of(method))
        .tag("outcome", MeterTags.of(outcome))
        .register(registry)
        .increment();
  }

  public void refresh(RefreshOutcome outcome) {
    Counter.builder(REFRESH)
        .description("Refresh-token rotations")
        .tag("outcome", MeterTags.of(outcome))
        .register(registry)
        .increment();
  }

  /**
   * A rate limit refused a request.
   *
   * @param scope the limit's name, a literal in the code that declares the limit; never the key
   *     that was limited
   */
  public void rateLimited(String scope) {
    Counter.builder(RATE_LIMITED)
        .description("Requests refused by a rate limit")
        .tag("scope", scope)
        .register(registry)
        .increment();
  }
}
