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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the per-identifier auth throttles (issue #899) — no Spring context, no Docker. */
class AuthIdentifierRateLimiterTest {

  private static final RateLimitProperties.Limit TWO = new RateLimitProperties.Limit(2, 3600);

  private final RateLimitProperties properties =
      new RateLimitProperties(
          List.of(), null, null, null, null, null, null, TWO, TWO, TWO, TWO, 100_000L);
  private final BucketRateLimitService buckets = new BucketRateLimitService(properties);
  private final AuthIdentifierRateLimiter limiter =
      new AuthIdentifierRateLimiter(buckets, properties);

  @Test
  void failedLoginsForOneAccountAreCappedWhateverTheSpelling() {
    limiter.beginLogin("Alice@Example.com");
    limiter.beginLogin("  alice@example.com ");

    assertThatThrownBy(() -> limiter.beginLogin("ALICE@EXAMPLE.COM"))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessage(LoginRateLimitFilter.MESSAGE)
        .satisfies(
            e -> assertThat(((RateLimitExceededException) e).retryAfterSeconds()).isPositive());
  }

  @Test
  void successfulLoginsDoNotCountAgainstTheAccount() {
    for (int i = 0; i < 5; i++) {
      limiter.beginLogin("bob");
      limiter.loginSucceeded("bob");
    }

    assertThatCode(() -> limiter.beginLogin("bob")).doesNotThrowAnyException();
  }

  @Test
  void accountsHaveIndependentBuckets() {
    limiter.beginLogin("carol");
    limiter.beginLogin("carol");

    assertThatCode(() -> limiter.beginLogin("dave")).doesNotThrowAnyException();
  }

  @Test
  void registerIsCappedPerEmail() {
    limiter.register("new@example.com");
    limiter.register("NEW@example.com");

    assertThatThrownBy(() -> limiter.register("new@example.com"))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessage(RegisterRateLimitFilter.MESSAGE);
  }

  @Test
  void forgotPasswordIsCappedPerEmailAndSeparateFromRegister() {
    limiter.register("eve@example.com");
    limiter.register("eve@example.com");

    limiter.forgotPassword("eve@example.com");
    limiter.forgotPassword("eve@example.com");

    assertThatThrownBy(() -> limiter.forgotPassword("eve@example.com"))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessage(ForgotPasswordRateLimitFilter.MESSAGE);
  }

  @Test
  void resetIsCappedPerTokenAndTokensAreCaseSensitive() {
    limiter.resetPassword("AbC");
    limiter.resetPassword("AbC");

    assertThatThrownBy(() -> limiter.resetPassword("AbC"))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessage(ResetPasswordRateLimitFilter.MESSAGE);
    assertThatCode(() -> limiter.resetPassword("abc")).doesNotThrowAnyException();
  }

  @Test
  void nullIdentifiersAreKeyedRatherThanRejected() {
    assertThatCode(() -> limiter.beginLogin(null)).doesNotThrowAnyException();
    assertThatCode(() -> limiter.resetPassword(null)).doesNotThrowAnyException();
  }

  @Test
  void keysAreHashesNotTheIdentifier() {
    String key = AuthIdentifierRateLimiter.normalizedHash(" Alice@Example.com ");

    assertThat(key).hasSize(64).doesNotContain("alice");
    assertThat(key).isEqualTo(AuthIdentifierRateLimiter.normalizedHash("alice@example.com"));
  }
}
