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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.web.security.ratelimit.AuthIdentifierRateLimiter.LoginAttempt;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
  private final LoginDeviceCookies deviceCookies =
      LoginDeviceCookiesTest.cookies(LoginDeviceCookiesTest.PROPERTIES, Instant.now());
  private final AuthIdentifierRateLimiter limiter =
      new AuthIdentifierRateLimiter(
          buckets, properties, new HttpClientIpResolver(properties), deviceCookies);

  /** Stands in for the credential check's lookup: three spellings of one account, nothing else. */
  private static final Set<String> ALICE_SPELLINGS =
      Set.of("alice", "alice@example.com", "Alice@Example.com");

  private LoginAttempt begin(String identifier, HttpServletRequest request) {
    Optional<UUID> account =
        identifier != null && ALICE_SPELLINGS.contains(identifier)
            ? Optional.of(ALICE)
            : Optional.empty();
    return limiter.beginLogin(identifier, account, request);
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
      assertThat(begin("alice", from("192.0." + net + ".1")).refused()).isFalse();
    }
  }

  @Test
  void usernameAndEmailOfOneAccountShareTheNetworkBucket() {
    begin("alice", from("203.0.113.5"));
    begin("Alice@Example.com", from("203.0.113.5"));

    assertThatThrownBy(() -> begin("alice@example.com", from("203.0.113.5")))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessage(LoginRateLimitFilter.MESSAGE)
        .satisfies(
            e -> assertThat(((RateLimitExceededException) e).retryAfterSeconds()).isPositive());
  }

  @Test
  void failuresFromOneNetworkDoNotAffectAnotherNetwork() {
    begin("alice", from("203.0.113.5"));
    begin("alice", from("203.0.113.77")); // same /24
    assertThatThrownBy(() -> begin("alice", from("203.0.113.9")))
        .isInstanceOf(RateLimitExceededException.class);

    LoginAttempt owner = begin("alice", from("198.51.100.20"));
    assertThat(owner.refused()).isFalse();
    limiter.loginSucceeded(owner);
  }

  @Test
  void aDrainedCeilingRefusesSilentlyInsteadOfWith429() {
    drainCeiling();

    // Another network, another spelling of the same account: no 429, just a refused attempt.
    LoginAttempt attempt = begin("alice@example.com", from("198.51.100.20"));
    assertThat(attempt.refused()).isTrue();
  }

  @Test
  void aSilentRefusalStillSpendsTheCallersNetworkToken() {
    drainCeiling();
    HttpServletRequest source = from("198.51.100.20");
    begin("alice", source);
    begin("alice", source);

    assertThatThrownBy(() -> begin("alice", source)).isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void aRecognisedDeviceIsExemptFromTheCeiling() {
    String device = deviceCookies.issue(ALICE).getValue();
    drainCeiling();

    LoginAttempt owner = begin("alice", from("198.51.100.20", device));

    assertThat(owner.refused()).isFalse();
    limiter.loginSucceeded(owner);
  }

  @Test
  void aRecognisedDeviceHasItsOwnBucketEvenInsideABusyNetwork() {
    String device = deviceCookies.issue(ALICE).getValue();
    begin("alice", from("203.0.113.5"));
    begin("alice", from("203.0.113.6"));
    assertThatThrownBy(() -> begin("alice", from("203.0.113.7")))
        .isInstanceOf(RateLimitExceededException.class);

    assertThatCode(() -> begin("alice", from("203.0.113.8", device))).doesNotThrowAnyException();
  }

  @Test
  void aRecognisedDeviceIsStillThrottledOnItsOwn() {
    String device = deviceCookies.issue(ALICE).getValue();
    begin("alice", from("203.0.113.5", device));
    begin("alice", from("198.51.100.5", device));

    assertThatThrownBy(() -> begin("alice", from("192.0.2.5", device)))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void aDeviceCookieForAnotherAccountIsNotRecognised() {
    String device = deviceCookies.issue(UUID.randomUUID()).getValue();
    drainCeiling();

    assertThat(begin("alice", from("198.51.100.20", device)).refused()).isTrue();
  }

  @Test
  void successfulLoginsDoNotCount() {
    for (int i = 0; i < 10; i++) {
      limiter.loginSucceeded(begin("alice", from("203.0.113.5")));
    }

    assertThat(begin("alice", from("203.0.113.5")).refused()).isFalse();
  }

  @Test
  void ipv6ClientsAreGroupedByTheirSlash56() {
    begin("alice", from("2001:db8:1:200::1"));
    begin("alice", from("2001:db8:1:2ff:ffff::9"));

    assertThatThrownBy(() -> begin("alice", from("2001:db8:1:250::abcd")))
        .isInstanceOf(RateLimitExceededException.class);
    assertThatCode(() -> begin("alice", from("2001:db8:1:300::1"))).doesNotThrowAnyException();
  }

  @Test
  void unknownIdentifiersAreThrottledLikeKnownOnes() {
    begin("ghost@example.com", from("203.0.113.5"));
    begin("GHOST@example.com", from("203.0.113.5"));

    assertThatThrownBy(() -> begin("ghost@example.com", from("203.0.113.5")))
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

    assertThat(begin("alice@example.com", from("203.0.113.5")).refused()).isFalse();
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
    assertThatCode(() -> begin(null, from("203.0.113.5"))).doesNotThrowAnyException();
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
