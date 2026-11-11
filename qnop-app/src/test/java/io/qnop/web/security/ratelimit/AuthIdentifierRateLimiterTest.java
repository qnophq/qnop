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
import io.qnop.web.security.ratelimit.AuthIdentifierRateLimiter.LoginAttempt;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
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
          List.of(), null, null, null, null, null, null, TWO, FIVE, TWO, TWO, FIVE, TWO, null,
          100_000L);
  private final BucketRateLimitService buckets = new BucketRateLimitService(properties);
  private final AuthService authService = mock(AuthService.class);
  private final LoginDeviceCookies deviceCookies =
      LoginDeviceCookiesTest.cookies(LoginDeviceCookiesTest.PROPERTIES, Instant.now());
  private final AuthIdentifierRateLimiter limiter =
      new AuthIdentifierRateLimiter(
          buckets, properties, new HttpClientIpResolver(properties), authService, deviceCookies);

  @BeforeEach
  void accounts() {
    when(authService.findLocalAccountId(any())).thenReturn(Optional.empty());
    when(authService.findLocalAccountId("alice")).thenReturn(Optional.of(ALICE));
    when(authService.findLocalAccountId("alice@example.com")).thenReturn(Optional.of(ALICE));
    when(authService.findLocalAccountId("Alice@Example.com")).thenReturn(Optional.of(ALICE));
  }

  private static HttpServletRequest from(String ip) {
    return from(ip, null);
  }

  private static HttpServletRequest from(String ip, String deviceCookie) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn(ip);
    when(request.getHeaders("X-Forwarded-For")).thenReturn(Collections.emptyEnumeration());
    if (deviceCookie != null) {
      when(request.getCookies())
          .thenReturn(new Cookie[] {new Cookie(LoginDeviceCookies.COOKIE_NAME, deviceCookie)});
    }
    return request;
  }

  /** Five failures from five networks: drains the ceiling (5) without touching any one network. */
  private void drainCeiling() {
    for (int net = 1; net <= 5; net++) {
      assertThat(limiter.beginLogin("alice", from("192.0." + net + ".1")).refused()).isFalse();
    }
  }

  @Test
  void usernameAndEmailOfOneAccountShareTheNetworkBucket() {
    limiter.beginLogin("alice", from("203.0.113.5"));
    limiter.beginLogin("Alice@Example.com", from("203.0.113.5"));

    assertThatThrownBy(() -> limiter.beginLogin("alice@example.com", from("203.0.113.5")))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessage(LoginRateLimitFilter.MESSAGE)
        .satisfies(
            e -> assertThat(((RateLimitExceededException) e).retryAfterSeconds()).isPositive());
  }

  @Test
  void failuresFromOneNetworkDoNotAffectAnotherNetwork() {
    limiter.beginLogin("alice", from("203.0.113.5"));
    limiter.beginLogin("alice", from("203.0.113.77")); // same /24
    assertThatThrownBy(() -> limiter.beginLogin("alice", from("203.0.113.9")))
        .isInstanceOf(RateLimitExceededException.class);

    LoginAttempt owner = limiter.beginLogin("alice", from("198.51.100.20"));
    assertThat(owner.refused()).isFalse();
    limiter.loginSucceeded(owner);
  }

  @Test
  void aDrainedCeilingRefusesSilentlyInsteadOfWith429() {
    drainCeiling();

    // Another network, another spelling of the same account: no 429, just a refused attempt.
    LoginAttempt attempt = limiter.beginLogin("alice@example.com", from("198.51.100.20"));
    assertThat(attempt.refused()).isTrue();
  }

  @Test
  void aSilentRefusalStillSpendsTheCallersNetworkToken() {
    drainCeiling();
    HttpServletRequest source = from("198.51.100.20");
    limiter.beginLogin("alice", source);
    limiter.beginLogin("alice", source);

    assertThatThrownBy(() -> limiter.beginLogin("alice", source))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void aRecognisedDeviceIsExemptFromTheCeiling() {
    String device = deviceCookies.issue(ALICE).getValue();
    drainCeiling();

    LoginAttempt owner = limiter.beginLogin("alice", from("198.51.100.20", device));

    assertThat(owner.refused()).isFalse();
    limiter.loginSucceeded(owner);
  }

  @Test
  void aRecognisedDeviceHasItsOwnBucketEvenInsideABusyNetwork() {
    String device = deviceCookies.issue(ALICE).getValue();
    limiter.beginLogin("alice", from("203.0.113.5"));
    limiter.beginLogin("alice", from("203.0.113.6"));
    assertThatThrownBy(() -> limiter.beginLogin("alice", from("203.0.113.7")))
        .isInstanceOf(RateLimitExceededException.class);

    assertThatCode(() -> limiter.beginLogin("alice", from("203.0.113.8", device)))
        .doesNotThrowAnyException();
  }

  @Test
  void aRecognisedDeviceIsStillThrottledOnItsOwn() {
    String device = deviceCookies.issue(ALICE).getValue();
    limiter.beginLogin("alice", from("203.0.113.5", device));
    limiter.beginLogin("alice", from("198.51.100.5", device));

    assertThatThrownBy(() -> limiter.beginLogin("alice", from("192.0.2.5", device)))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void aDeviceCookieForAnotherAccountIsNotRecognised() {
    String device = deviceCookies.issue(UUID.randomUUID()).getValue();
    drainCeiling();

    assertThat(limiter.beginLogin("alice", from("198.51.100.20", device)).refused()).isTrue();
  }

  @Test
  void successfulLoginsDoNotCount() {
    for (int i = 0; i < 10; i++) {
      limiter.loginSucceeded(limiter.beginLogin("alice", from("203.0.113.5")));
    }

    assertThat(limiter.beginLogin("alice", from("203.0.113.5")).refused()).isFalse();
  }

  @Test
  void ipv6ClientsAreGroupedByTheirSlash56() {
    limiter.beginLogin("alice", from("2001:db8:1:200::1"));
    limiter.beginLogin("alice", from("2001:db8:1:2ff:ffff::9"));

    assertThatThrownBy(() -> limiter.beginLogin("alice", from("2001:db8:1:250::abcd")))
        .isInstanceOf(RateLimitExceededException.class);
    assertThatCode(() -> limiter.beginLogin("alice", from("2001:db8:1:300::1")))
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
    assertThat(AuthIdentifierRateLimiter.loginIdentifierHash("Ghost@Example.com"))
        .isEqualTo(AuthIdentifierRateLimiter.loginIdentifierHash("ghost@example.com"));
    assertThat(AuthIdentifierRateLimiter.loginIdentifierHash("Ghost"))
        .isNotEqualTo(AuthIdentifierRateLimiter.loginIdentifierHash("ghost"));
    assertThat(AuthIdentifierRateLimiter.loginIdentifierHash("ghost@example.com"))
        .hasSize(64)
        .doesNotContain("ghost");
  }

  @Test
  void resetRequestsAreCappedPerAddressAndNetwork() {
    assertThat(limiter.mayRequestPasswordReset("bob@example.com", from("203.0.113.5"))).isTrue();
    assertThat(limiter.mayRequestPasswordReset("BOB@example.com", from("203.0.113.6"))).isTrue();

    assertThatThrownBy(
            () -> limiter.mayRequestPasswordReset("bob@example.com", from("203.0.113.7")))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessage(ForgotPasswordRateLimitFilter.MESSAGE);
    // The owner's own network is unaffected.
    assertThat(limiter.mayRequestPasswordReset("bob@example.com", from("198.51.100.5"))).isTrue();
  }

  @Test
  void aDrainedResetCeilingSuppressesTheMailWithout429() {
    for (int net = 1; net <= 5; net++) {
      assertThat(limiter.mayRequestPasswordReset("carol@example.com", from("192.0." + net + ".1")))
          .isTrue();
    }

    assertThat(limiter.mayRequestPasswordReset("carol@example.com", from("198.51.100.5")))
        .isFalse();
  }

  @Test
  void drainedMailboxBucketsDoNotAffectTheOwnersLogin() {
    limiter.register("alice@example.com");
    limiter.register("alice@example.com");
    for (int net = 1; net <= 6; net++) {
      limiter.mayRequestPasswordReset("alice@example.com", from("192.0." + net + ".1"));
    }

    assertThat(limiter.beginLogin("alice@example.com", from("203.0.113.5")).refused()).isFalse();
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
