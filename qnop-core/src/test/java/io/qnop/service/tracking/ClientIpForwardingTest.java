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

/** What each mode puts on the wire (issue #712). */
class ClientIpForwardingTest {

  @Test
  @DisplayName("full forwards the exact address, anonymized truncates, none sends nothing")
  void modesDiffer() {
    assertThat(ClientIpForwarding.FULL.format("203.0.113.42")).contains("203.0.113.42");
    assertThat(ClientIpForwarding.ANONYMIZED.format("203.0.113.42")).contains("203.0.113.0");
    assertThat(ClientIpForwarding.NONE.format("203.0.113.42")).isEmpty();
  }

  @Test
  @DisplayName("an address this server cannot read travels in no mode, full included")
  void unparseableTravelsNowhere() {
    // The guarantee that made the raw-passthrough shortcut unacceptable: behind a
    // trusted proxy, X-Forwarded-For carries whatever that proxy passed along, and
    // it would otherwise reach an outgoing header unread.
    for (String bad :
        new String[] {null, "", "   ", "not-an-address", "203.0.113.999", "evil\r\nX-Foo: bar"}) {
      assertThat(ClientIpForwarding.FULL.format(bad)).as("FULL(%s)", bad).isEmpty();
      assertThat(ClientIpForwarding.ANONYMIZED.format(bad)).as("ANONYMIZED(%s)", bad).isEmpty();
    }
  }

  @Test
  @DisplayName("an IPv6 address survives full and loses its host half when anonymized")
  void ipv6() {
    String address = "2001:db8:85a3:1:1a2b:3c4d:5e6f:7a8b";

    assertThat(ClientIpForwarding.FULL.format(address)).isPresent();
    assertThat(ClientIpForwarding.ANONYMIZED.format(address))
        .hasValueSatisfying(value -> assertThat(value).doesNotContain("7a8b"));
  }

  @Test
  @DisplayName("an unreadable setting falls to anonymized, never to full")
  void parseFallsToThePrivacyPreservingOption() {
    // The direction of the fallback is the point: a typo in a settings row must not
    // start forwarding personal data.
    assertThat(ClientIpForwarding.parse("full")).isEqualTo(ClientIpForwarding.FULL);
    assertThat(ClientIpForwarding.parse("FULL")).isEqualTo(ClientIpForwarding.FULL);
    assertThat(ClientIpForwarding.parse("none")).isEqualTo(ClientIpForwarding.NONE);
    assertThat(ClientIpForwarding.parse("anonymized")).isEqualTo(ClientIpForwarding.ANONYMIZED);
    assertThat(ClientIpForwarding.parse("nonsense")).isEqualTo(ClientIpForwarding.ANONYMIZED);
    assertThat(ClientIpForwarding.parse(null)).isEqualTo(ClientIpForwarding.ANONYMIZED);
  }
}
