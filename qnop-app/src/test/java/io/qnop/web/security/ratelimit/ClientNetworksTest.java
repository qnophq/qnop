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

import org.junit.jupiter.api.Test;

/** Unit tests for client-address grouping (issues #898/#899). */
class ClientNetworksTest {

  @Test
  void perIpKeepsIpv4AddressesApart() {
    assertThat(ClientNetworks.perIpKey("203.0.113.5")).isEqualTo("203.0.113.5");
    assertThat(ClientNetworks.perIpKey("203.0.113.6")).isNotEqualTo("203.0.113.5");
  }

  @Test
  void perIpGroupsIpv6BySlash56() {
    assertThat(ClientNetworks.perIpKey("2001:db8:1:2ab::1"))
        .isEqualTo(ClientNetworks.perIpKey("2001:db8:1:2ff:ffff:ffff:ffff:ffff"))
        .isNotEqualTo(ClientNetworks.perIpKey("2001:db8:1:300::1"));
  }

  @Test
  void ipv4MappedIpv6IsTreatedAsIpv4() {
    assertThat(ClientNetworks.perIpKey("::ffff:203.0.113.5")).isEqualTo("203.0.113.5");
    assertThat(ClientNetworks.networkKey("::ffff:203.0.113.5"))
        .isEqualTo(ClientNetworks.networkKey("203.0.113.99"));
  }

  @Test
  void networkGroupsIpv4BySlash24() {
    assertThat(ClientNetworks.networkKey("203.0.113.5"))
        .isEqualTo(ClientNetworks.networkKey("203.0.113.250"))
        .isNotEqualTo(ClientNetworks.networkKey("203.0.114.5"));
  }

  @Test
  void nonLiteralsAreUsedAsIs() {
    assertThat(ClientNetworks.perIpKey("not-an-ip")).isEqualTo("not-an-ip");
    assertThat(ClientNetworks.networkKey("not-an-ip")).isEqualTo("raw:not-an-ip");
  }
}
