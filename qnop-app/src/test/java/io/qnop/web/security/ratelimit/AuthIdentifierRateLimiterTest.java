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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for the per-identifier auth throttles (issue #899) — no Spring context, no Docker. */
class AuthIdentifierRateLimiterTest {

  private static final RateLimitProperties.Limit TWO = new RateLimitProperties.Limit(2, 3600);
  private static final RateLimitProperties.Limit FIVE = new RateLimitProperties.Limit(5, 3600);
  private static final UUID ALICE = UUID.randomUUID();

  private final RateLimitProperties properties =
      new RateLimitProperties(
          List.of(), null, null, null, null, null, null, TWO, FIVE, TWO, TWO, TWO, 100_000L);
  private final BucketRateLimitService buckets = new BucketRateLimitService(properties);
  private final AuthService authService = mock(AuthService.class);
  private final AuthIdentifierRateLimiter limiter =
      new AuthIdentifierRateLimiter(
          buckets, properties, new HttpClientIpResolver(properties), authService);

  @BeforeEach
  void accounts() {
    when(authService.findLocalAccountId(any())).thenReturn(Optional.empty());
    when(authService.findLocalAccountId("alice")).thenReturn(Optional.of(ALICE));
    when(authService.findLocalAccountId("alice@example.com")).thenReturn(Optional.of(ALICE));
    when(authService.findLocalAccountId("Alice@Example.com")).thenReturn(Optional.of(ALICE));
  }

  private static HttpServletRequest from(String ip) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn(ip);
    when(request.getHeaders("X-Forwarded-For")).thenReturn(Collections.emptyEnumeration());
    return request;
  }

  @Test
  void usernameAndEmailOfOneAccountShareItsBuckets() {
    limiter.beginLogin("alice", from("203.0.113.5"));
    limiter.beginLogin("Alice@Example.com", from("203.0.113.5"));

    assertThatThrownBy(() -> limiter.beginLogin("alice@example.com", from("203.0.113.5")))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessage(LoginRateLimitFilter.MESSAGE)
        .satisfies(
            e -> assertThat(((RateLimitExceededException) e).retryAfterSeconds()).isPositive());
  }

  @Test
  void failuresFromOneNetworkDoNotLockTheOwnerOutElsewhere() {
    limiter.beginLogin("alice", from("203.0.113.5"));
    limiter.beginLogin("alice", from("203.0.113.77")); // same /24
    assertThatThrownBy(() -> limiter.beginLogin("alice", from("203.0.113.9")))
        .isInstanceOf(RateLimitExceededException.class);

    // The owner, from another network, still gets to try — and succeeds.
    var attempt = limiter.beginLogin("alice", from("198.51.100.20"));
    limiter.loginSucceeded(attempt);
    assertThatCode(() -> limiter.beginLogin("alice", from("198.51.100.20")))
        .doesNotThrowAnyException();
  }

  @Test
  void theOverallCeilingCapsFailuresAcrossNetworks() {
    for (int net = 1; net <= 5; net++) {
      limiter.beginLogin("alice", from("192.0." + net + ".1"));
    }

    assertThatThrownBy(() -> limiter.beginLogin("alice", from("198.51.100.20")))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void aRefusalByTheCeilingIsNotChargedToTheNetwork() {
    for (int net = 1; net <= 5; net++) {
      limiter.beginLogin("alice", from("192.0." + net + ".1"));
    }
    HttpServletRequest owner = from("198.51.100.20");
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> limiter.beginLogin("alice", owner))
          .isInstanceOf(RateLimitExceededException.class);
    }

    // The ceiling refills one token; the owner's network bucket was never drained by the refusals.
    buckets.refund(AuthIdentifierRateLimiter.LOGIN_ACCOUNT, "user:" + ALICE);
    assertThatCode(() -> limiter.beginLogin("alice", owner)).doesNotThrowAnyException();
  }

  @Test
  void successfulLoginsDoNotCount() {
    for (int i = 0; i < 10; i++) {
      limiter.loginSucceeded(limiter.beginLogin("alice", from("203.0.113.5")));
    }

    assertThatCode(() -> limiter.beginLogin("alice", from("203.0.113.5")))
        .doesNotThrowAnyException();
  }

  @Test
  void ipv6ClientsAreGroupedByTheirSlash64() {
    limiter.beginLogin("alice", from("2001:db8:1:2::1"));
    limiter.beginLogin("alice", from("2001:db8:1:2:ffff::9"));

    assertThatThrownBy(() -> limiter.beginLogin("alice", from("2001:db8:1:2::abcd")))
        .isInstanceOf(RateLimitExceededException.class);
    assertThatCode(() -> limiter.beginLogin("alice", from("2001:db8:1:3::1")))
        .doesNotThrowAnyException();
  }

  @Test
  void unknownIdentifiersAreThrottledLikeKnownOnes() {
    limiter.beginLogin("ghost@example.com", from("203.0.113.5"));
    limiter.beginLogin("GHOST@example.com", from("203.0.113.5"));

    assertThatThrownBy(() -> limiter.beginLogin("ghost@example.com", from("203.0.113.5")))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void unknownIdentifiersFoldLikeTheAccountLookup() {
    // E-mail addresses match case-insensitively, usernames exactly.
    assertThat(AuthIdentifierRateLimiter.loginIdentifierHash("Ghost@Example.com"))
        .isEqualTo(AuthIdentifierRateLimiter.loginIdentifierHash("ghost@example.com"));
    assertThat(AuthIdentifierRateLimiter.loginIdentifierHash("Ghost"))
        .isNotEqualTo(AuthIdentifierRateLimiter.loginIdentifierHash("ghost"));
    assertThat(AuthIdentifierRateLimiter.loginIdentifierHash("ghost@example.com"))
        .hasSize(64)
        .doesNotContain("ghost");
  }

  @Test
  void networkPrefixes() {
    assertThat(AuthIdentifierRateLimiter.networkPrefix("203.0.113.5"))
        .isEqualTo(AuthIdentifierRateLimiter.networkPrefix("203.0.113.250"))
        .isNotEqualTo(AuthIdentifierRateLimiter.networkPrefix("203.0.114.5"));
    assertThat(AuthIdentifierRateLimiter.networkPrefix("not-an-ip")).isEqualTo("raw:not-an-ip");
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
  void drainedMailboxBucketsDoNotAffectTheOwnersLogin() {
    limiter.register("alice@example.com");
    limiter.register("alice@example.com");
    limiter.forgotPassword("alice@example.com");
    limiter.forgotPassword("alice@example.com");

    assertThatThrownBy(() -> limiter.forgotPassword("alice@example.com"))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessage(ForgotPasswordRateLimitFilter.MESSAGE);
    assertThatCode(() -> limiter.beginLogin("alice@example.com", from("203.0.113.5")))
        .doesNotThrowAnyException();
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
    assertThatCode(() -> limiter.beginLogin(null, from("203.0.113.5"))).doesNotThrowAnyException();
    assertThatCode(() -> limiter.register(null)).doesNotThrowAnyException();
    assertThatCode(() -> limiter.resetPassword(null)).doesNotThrowAnyException();
  }

  @Test
  void mailboxKeysAreHashesNotTheAddress() {
    String key = AuthIdentifierRateLimiter.normalizedHash(" Alice@Example.com ");

    assertThat(key).hasSize(64).doesNotContain("alice");
    assertThat(key).isEqualTo(AuthIdentifierRateLimiter.normalizedHash("alice@example.com"));
  }
}
