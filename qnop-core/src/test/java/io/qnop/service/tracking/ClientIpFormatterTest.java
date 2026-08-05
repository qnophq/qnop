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
package io.qnop.service.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Truncating the visitor address that the proxy forwards (issue #666). */
class ClientIpFormatterTest {

  @Test
  @DisplayName("IPv4 keeps its network, loses its host")
  void truncatesIpv4() {
    assertThat(ClientIpFormatter.anonymize("203.0.113.42")).contains("203.0.113.0");
    assertThat(ClientIpFormatter.anonymize("10.1.2.3")).contains("10.1.2.0");
  }

  @Test
  @DisplayName("IPv6 keeps its /64")
  void truncatesIpv6() {
    // A /64 is one subscriber line, not one device — the same trade the IPv4 /24
    // makes, at the size IPv6 hands out.
    assertThat(ClientIpFormatter.anonymize("2001:db8:85a3:1:1a2b:3c4d:5e6f:7a8b"))
        .hasValueSatisfying(
            value -> assertThat(value).startsWith("2001:db8:85a3:1:").endsWith("0:0:0:0"));
  }

  @Test
  @DisplayName("brackets around an IPv6 literal are tolerated")
  void tolerName() {
    assertThat(ClientIpFormatter.anonymize("[2001:db8::1]")).isPresent();
  }

  @Test
  @DisplayName("forwards nothing rather than guessing")
  void failsClosed() {
    assertThat(ClientIpFormatter.anonymize(null)).isEmpty();
    assertThat(ClientIpFormatter.anonymize("")).isEmpty();
    assertThat(ClientIpFormatter.anonymize("not-an-ip")).isEmpty();
    // A hostname must never be resolved on a request path, so it is simply refused.
    assertThat(ClientIpFormatter.anonymize("analytics.example.com")).isEmpty();
    assertThat(ClientIpFormatter.anonymize("999.1.1.1")).isEmpty();
  }
}
