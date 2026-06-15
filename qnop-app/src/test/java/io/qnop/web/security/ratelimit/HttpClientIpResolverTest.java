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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the trusted-proxy client-IP resolution (ADR-0027). The spoofing defences here are
 * the security crux of per-IP rate limiting, so they are covered without a Spring context or
 * Docker.
 */
class HttpClientIpResolverTest {

  private static HttpServletRequest request(String remoteAddr, String xForwardedFor) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn(remoteAddr);
    when(request.getHeader("X-Forwarded-For")).thenReturn(xForwardedFor);
    return request;
  }

  private static HttpClientIpResolver resolver(List<String> trustedCidrs) {
    return new HttpClientIpResolver(
        new RateLimitProperties(trustedCidrs, null, null, null, null, null));
  }

  @Test
  void usesRemoteAddrWhenNoForwardedHeader() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("203.0.113.5", null))).isEqualTo("203.0.113.5");
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
  void takesLeftmostForwardedEntryFromTrustedProxy() {
    HttpClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.7, 10.1.2.3")))
        .isEqualTo("198.51.100.7");
  }
}
