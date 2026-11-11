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

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the trusted-proxy client-IP resolution (ADR-0027). The spoofing defences here are
 * the security crux of per-IP rate limiting, so they are covered without a Spring context or
 * Docker.
 */
class HttpClientIpResolverTest {

  private static HttpServletRequest request(String remoteAddr, String... xForwardedFor) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn(remoteAddr);
    List<String> headers = xForwardedFor.length == 0 ? List.<String>of() : List.of(xForwardedFor);
    when(request.getHeaders("X-Forwarded-For")).thenReturn(Collections.enumeration(headers));
    return request;
  }

  private static HttpClientIpResolver resolver(List<String> trustedCidrs) {
    return new HttpClientIpResolver(RateLimitPropertiesFixture.withTrustedProxies(trustedCidrs));
  }

  @Test
  void usesRemoteAddrWhenNoForwardedHeader() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("203.0.113.5"))).isEqualTo("203.0.113.5");
  }

  @Test
  void ignoresForwardedHeaderWhenNoProxyIsTrusted() {
    // Secure default: with an empty trust list the header is attacker-controlled and ignored.
    HttpClientIpResolver resolver = resolver(List.of());

    assertThat(resolver.resolve(request("203.0.113.5", "1.1.1.1"))).isEqualTo("203.0.113.5");
  }

  @Test
  void ignoresForwardedHeaderFromUntrustedImmediateHop() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    // remoteAddr is not in the trusted range, so X-Forwarded-For cannot be believed.
    assertThat(resolver.resolve(request("203.0.113.5", "1.1.1.1"))).isEqualTo("203.0.113.5");
  }

  @Test
  void trustsForwardedHeaderFromTrustedProxy() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.7"))).isEqualTo("198.51.100.7");
  }

  @Test
  void takesRightmostUntrustedEntryWhenTheProxyAppends() {
    // An appending proxy puts the peer it saw on the right; anything to its left is
    // client-supplied.
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "192.0.2.44, 198.51.100.7")))
        .isEqualTo("198.51.100.7");
  }

  @Test
  void skipsTrustedHopsInAMultiHopChain() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8", "172.16.0.0/12"));

    assertThat(
            resolver.resolve(request("10.1.2.3", "192.0.2.44, 198.51.100.7, 172.16.5.5, 10.9.9.9")))
        .isEqualTo("198.51.100.7");
  }

  @Test
  void takesLeftmostEntryWhenEveryHopIsTrusted() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "10.0.0.5, 10.0.0.6"))).isEqualTo("10.0.0.5");
  }

  @Test
  void joinsRepeatedHeaderLinesInOrder() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "192.0.2.44", "198.51.100.7, 10.0.0.6")))
        .isEqualTo("198.51.100.7");
  }

  @Test
  void fallsBackToRemoteAddrWhenTheRightmostEntryIsMalformed() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.7, not-an-ip")))
        .isEqualTo("10.1.2.3");
  }

  @Test
  void stopsAtTheLastTrustedHopWhenAnEarlierEntryIsMalformed() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.7, unknown, 10.0.0.6")))
        .isEqualTo("10.0.0.6");
  }

  @Test
  void treatsEmptyEntriesAndHostnamesAsMalformed() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.7, ,"))).isEqualTo("10.1.2.3");
    assertThat(resolver.resolve(request("10.1.2.3", "localhost"))).isEqualTo("10.1.2.3");
    assertThat(resolver.resolve(request("10.1.2.3", "256.1.1.1"))).isEqualTo("10.1.2.3");
  }

  @Test
  void ignoresBlankHeader() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "  "))).isEqualTo("10.1.2.3");
  }

  @Test
  void stripsPortsAndBracketsFromEntries() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.7:4711")))
        .isEqualTo("198.51.100.7");
    assertThat(resolver.resolve(request("10.1.2.3", "[2001:db8::1]:443")))
        .isEqualTo("2001:db8:0:0:0:0:0:1");
    assertThat(resolver.resolve(request("10.1.2.3", "2001:db8::1")))
        .isEqualTo("2001:db8:0:0:0:0:0:1");
  }

  @Test
  void canonicalisesIpv4MappedEntries() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "::ffff:198.51.100.7")))
        .isEqualTo("198.51.100.7");
  }

  @Test
  void neverTreatsAnEntryThatCouldReachTheNameServiceAsALiteral() {
    // Only strings starting with a hex digit or ':' are parsed by the JDK without a lookup.
    assertThat(HttpClientIpResolver.parseLiteral(".:")).isEmpty();
    assertThat(HttpClientIpResolver.parseLiteral(".1:2")).isEmpty();
    assertThat(HttpClientIpResolver.parseLiteral("[.:]")).isEmpty();
    assertThat(HttpClientIpResolver.parseLiteral("g::1")).isEmpty();
    assertThat(HttpClientIpResolver.parseLiteral("abc")).isEmpty();
    assertThat(HttpClientIpResolver.parseLiteral("::1")).contains("0:0:0:0:0:0:0:1");
  }

  @Test
  void matchesIpv6TrustedProxies() {
    HttpClientIpResolver resolver = resolver(List.of("fd00::/8"));

    assertThat(resolver.resolve(request("fd00::1", "198.51.100.7, fd00::2")))
        .isEqualTo("198.51.100.7");
  }
}
