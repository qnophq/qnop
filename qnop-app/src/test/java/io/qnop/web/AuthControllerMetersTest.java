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
package io.qnop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.api.v1.model.LoginRequest;
import io.qnop.observability.metrics.AuthMeters;
import io.qnop.security.QnopProperties;
import io.qnop.service.AuthService;
import io.qnop.service.JwtTokenService;
import io.qnop.service.RefreshTokenService;
import io.qnop.service.TokenRevocationService;
import io.qnop.web.security.RefreshTokenCookieFactory;
import io.qnop.web.security.ratelimit.AuthIdentifierRateLimiter;
import io.qnop.web.security.ratelimit.AuthIdentifierRateLimiter.LoginAttempt;
import io.qnop.web.security.ratelimit.LoginDeviceCookies;
import io.qnop.web.security.ratelimit.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * The login outcomes the client cannot tell apart, but the operator must (issue #962): a refusal by
 * a per-account limit is {@code throttled}, never {@code rejected}.
 */
class AuthControllerMetersTest {

  private final AuthService authService = mock(AuthService.class);
  private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
  private final AuthIdentifierRateLimiter limiter = mock(AuthIdentifierRateLimiter.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AuthController controller =
      new AuthController(
          authService,
          mock(JwtTokenService.class),
          refreshTokens,
          mock(TokenRevocationService.class),
          mock(RefreshTokenCookieFactory.class),
          mock(QnopProperties.class),
          limiter,
          mock(HttpServletRequest.class),
          mock(LoginDeviceCookies.class),
          new AuthMeters(registry));

  private static LoginRequest request() {
    return new LoginRequest().usernameOrEmail("someone").password("whatever");
  }

  /** Runs the gate the way the service does, then reports the credentials as {@code result}. */
  @SuppressWarnings("unchecked")
  private void credentialsGive(Optional<UUID> result) {
    when(authService.authenticate(any(), any(), any()))
        .thenAnswer(
            call -> {
              Predicate<Optional<UUID>> gate = call.getArgument(2);
              boolean admitted = gate.test(Optional.empty());
              return admitted ? result : Optional.empty();
            });
  }

  @Test
  @DisplayName("a 429 from the account's source bucket is counted as throttled")
  void sourceBucketRefusalIsThrottled() {
    credentialsGive(Optional.of(UUID.randomUUID()));
    when(limiter.beginLogin(any(), any(), any()))
        .thenThrow(new RateLimitExceededException("Too many login attempts.", 30));

    assertThatThrownBy(() -> controller.login(request()))
        .isInstanceOf(RateLimitExceededException.class);

    assertThat(logins("throttled")).isEqualTo(1.0);
    assertThat(logins("rejected")).isZero();
  }

  @Test
  @DisplayName("a silent refusal by the account ceiling answers 401 but is counted as throttled")
  void silentCeilingRefusalIsThrottled() {
    credentialsGive(Optional.of(UUID.randomUUID()));
    when(limiter.beginLogin(any(), any(), any())).thenReturn(new LoginAttempt("net", null, true));

    assertThatThrownBy(() -> controller.login(request()))
        .isInstanceOf(ResponseStatusException.class);

    assertThat(logins("throttled")).isEqualTo(1.0);
    assertThat(logins("rejected")).isZero();
  }

  @Test
  @DisplayName("wrong credentials within the limits are counted as rejected")
  void wrongCredentialsAreRejected() {
    credentialsGive(Optional.empty());
    when(limiter.beginLogin(any(), any(), any())).thenReturn(new LoginAttempt("net", "acc", false));

    assertThatThrownBy(() -> controller.login(request()))
        .isInstanceOf(ResponseStatusException.class);

    assertThat(logins("rejected")).isEqualTo(1.0);
    assertThat(logins("throttled")).isZero();
  }

  @Test
  @DisplayName("a failure that is not the user's is an error, and exactly one outcome is counted")
  void unexpectedFailuresAreErrors() {
    when(authService.authenticate(any(), any(), any()))
        .thenThrow(new IllegalStateException("database down"));

    assertThatThrownBy(() -> controller.login(request())).isInstanceOf(IllegalStateException.class);

    assertThat(logins("error")).isEqualTo(1.0);
    assertThat(registry.find(AuthMeters.LOGIN).counters()).hasSize(1);
  }

  @Test
  @DisplayName("right credentials without a session are an error, not a success")
  void noSessionIsNotASuccess() {
    credentialsGive(Optional.of(UUID.randomUUID()));
    when(limiter.beginLogin(any(), any(), any())).thenReturn(new LoginAttempt("net", "acc", false));
    when(refreshTokens.issue(any())).thenThrow(new IllegalStateException("database down"));

    assertThatThrownBy(() -> controller.login(request())).isInstanceOf(IllegalStateException.class);

    assertThat(logins("error")).isEqualTo(1.0);
    assertThat(logins("success")).isZero();
  }

  private double logins(String outcome) {
    var counter =
        registry.find(AuthMeters.LOGIN).tag("method", "local").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }
}
