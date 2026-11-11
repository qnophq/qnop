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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.security.JwtKeyService;
import io.qnop.security.QnopProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/** Unit tests for the recognised-device cookie (issue #899) — no Spring context, no Docker. */
class LoginDeviceCookiesTest {

  static final QnopProperties PROPERTIES = properties("unit-test-jwt-secret-0123456789abcdef");
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
  private static final UUID ALICE = UUID.randomUUID();

  static QnopProperties properties(String jwtSecret) {
    return new QnopProperties(
        new QnopProperties.Auth(
            jwtSecret,
            "unit-test-encryption-key-0123456789",
            "0123456789abcdef",
            Duration.ofMinutes(15),
            Duration.ofDays(7),
            "qnop",
            Boolean.TRUE,
            null),
        new QnopProperties.Cors(null));
  }

  static LoginDeviceCookies cookies(QnopProperties properties, Instant now) {
    return new LoginDeviceCookies(
        new JwtKeyService(properties),
        RateLimitPropertiesFixture.defaults(),
        properties,
        Clock.fixed(now, ZoneOffset.UTC));
  }

  static HttpServletRequest presenting(String cookieValue) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getCookies())
        .thenReturn(new Cookie[] {new Cookie(LoginDeviceCookies.COOKIE_NAME, cookieValue)});
    return request;
  }

  private final LoginDeviceCookies cookies = cookies(PROPERTIES, NOW);

  @Test
  void issuesAHardenedCookieScopedToLogin() {
    ResponseCookie cookie = cookies.issue(ALICE);

    assertThat(cookie.getName()).isEqualTo("qnop_device");
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.getSameSite()).isEqualTo("Strict");
    assertThat(cookie.getPath()).isEqualTo("/api/v1/auth/login");
    assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(30));
    assertThat(cookie.getValue()).doesNotContain(ALICE.toString());
  }

  @Test
  void recognisesItsOwnCookieForTheBoundAccount() {
    String value = cookies.issue(ALICE).getValue();

    assertThat(cookies.recognisedDevice(presenting(value), ALICE)).isPresent();
  }

  @Test
  void eachIssueIsADistinctDevice() {
    String first = cookies.issue(ALICE).getValue();
    String second = cookies.issue(ALICE).getValue();

    assertThat(cookies.verify(first, ALICE)).isNotEqualTo(cookies.verify(second, ALICE));
  }

  @Test
  void rejectsACookieBoundToAnotherAccount() {
    String value = cookies.issue(ALICE).getValue();

    assertThat(cookies.recognisedDevice(presenting(value), UUID.randomUUID())).isEmpty();
  }

  @Test
  void rejectsATamperedCookie() {
    String value = cookies.issue(ALICE).getValue();
    // Flip the first payload character: its six bits are all significant.
    String tampered = (value.charAt(0) == 'A' ? 'B' : 'A') + value.substring(1);

    assertThat(cookies.verify(tampered, ALICE)).isEmpty();
    assertThat(cookies.verify("garbage", ALICE)).isEmpty();
    assertThat(cookies.verify("a.b.c", ALICE)).isEmpty();
    assertThat(cookies.verify(null, ALICE)).isEmpty();
  }

  @Test
  void rejectsACookieSignedWithAnotherSecret() {
    String foreign =
        cookies(properties("another-jwt-secret-0123456789abcdefgh"), NOW).issue(ALICE).getValue();

    assertThat(cookies.verify(foreign, ALICE)).isEmpty();
  }

  @Test
  void expiresAfterItsLifetime() {
    String value = cookies.issue(ALICE).getValue();

    assertThat(cookies(PROPERTIES, NOW.plus(Duration.ofDays(29))).verify(value, ALICE)).isPresent();
    assertThat(cookies(PROPERTIES, NOW.plus(Duration.ofDays(30))).verify(value, ALICE)).isEmpty();
    assertThat(cookies(PROPERTIES, NOW.minus(Duration.ofMinutes(1))).verify(value, ALICE))
        .isEmpty();
  }

  @Test
  void noCookiesMeansNoDevice() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    assertThat(cookies.recognisedDevice(request, ALICE)).isEmpty();
  }
}
