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
package io.qnop.service.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Table-driven classification of every address form the guard must refuse (issue #904). */
class OutboundAddressPolicyTest {

  @ParameterizedTest(name = "{0} is blocked")
  @ValueSource(
      strings = {
        // IPv4 special-purpose ranges
        "0.0.0.0",
        "0.1.2.3",
        "10.0.0.1",
        "10.255.255.255",
        "100.64.0.1",
        "100.127.255.254",
        "127.0.0.1",
        "127.10.20.30",
        "169.254.0.1",
        "169.254.169.254",
        "169.254.170.2",
        "172.16.0.1",
        "172.31.255.255",
        "192.0.0.8",
        "192.168.1.1",
        "198.18.0.1",
        "198.19.255.255",
        "224.0.0.1",
        "239.255.255.250",
        "240.0.0.1",
        "255.255.255.255",
        // explicit cloud metadata addresses
        "100.100.100.200",
        "168.63.129.16",
        "192.0.0.192",
        "fd00:ec2::254",
        // IPv6 special-purpose ranges
        "::",
        "::1",
        "fe80::1",
        "febf::1",
        "fec0::1",
        "fc00::1",
        "fdff:ffff::1",
        "ff02::1",
        "ff05::2",
        // IPv4 embedded in IPv6
        "::127.0.0.1",
        "::10.0.0.1",
        "::169.254.169.254",
        "64:ff9b::7f00:1",
        "64:ff9b::a9fe:a9fe",
        "64:ff9b::a00:1",
        "64:ff9b:1::1",
        "2002:7f00:1::1",
        "2002:a9fe:a9fe::1",
        // special-purpose IPv6 / IPv4 blocks
        "2001::1",
        "2001:0:4136:e378:8000:63bf:3fff:fdd2",
        "100::1",
        "100::ffff:ffff:ffff:ffff",
        "2001:db8::1",
        "2001:db8:ffff::1",
        "2001:2::1",
        "2001:2:0:ffff::1",
        "192.88.99.1"
      })
  void blocks(String literal) throws Exception {
    assertThat(OutboundAddressPolicy.isBlocked(InetAddress.getByName(literal))).isTrue();
  }

  @ParameterizedTest(name = "{0} is allowed")
  @ValueSource(
      strings = {
        "8.8.8.8",
        "1.1.1.1",
        "93.184.216.34",
        "100.63.255.255",
        "100.128.0.1",
        "172.15.255.255",
        "172.32.0.1",
        "192.0.1.1",
        "192.169.0.1",
        "198.17.255.255",
        "198.20.0.1",
        "223.255.255.255",
        "2001:4860:4860::8888",
        "2606:4700:4700::1111",
        "2002:808:808::1",
        "::8.8.8.8",
        // NAT64 (DNS64 on IPv6-only networks) to a public IPv4 host is a public destination
        "64:ff9b::808:808",
        "64:ff9b::5db8:d822",
        "2001:3::1",
        "2001:db9::1",
        "100:0:0:1::1",
        "192.88.98.1"
      })
  void allows(String literal) throws Exception {
    assertThat(OutboundAddressPolicy.isBlocked(InetAddress.getByName(literal))).isFalse();
  }

  @Test
  @DisplayName("an IPv4-mapped IPv6 address is judged by its IPv4 part, even kept as IPv6 bytes")
  void ipv4MappedIsJudgedByEmbeddedAddress() throws Exception {
    // InetAddress.getByName("::ffff:…") already collapses to Inet4Address; build the raw IPv6
    // form explicitly so the IPv6 branch is exercised too.
    byte[] loopback = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 127, 0, 0, 1};
    byte[] metadata = {
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      (byte) 0xff,
      (byte) 0xff,
      (byte) 169,
      (byte) 254,
      (byte) 169,
      (byte) 254
    };
    byte[] publicV4 = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 8, 8, 8, 8};

    assertThat(OutboundAddressPolicy.isBlocked(Inet6Address.getByAddress(null, loopback, 0)))
        .isTrue();
    assertThat(OutboundAddressPolicy.isBlocked(Inet6Address.getByAddress(null, metadata, 0)))
        .isTrue();
    assertThat(OutboundAddressPolicy.isBlocked(Inet6Address.getByAddress(null, publicV4, 0)))
        .isFalse();
    assertThat(OutboundAddressPolicy.isBlocked(InetAddress.getByName("::ffff:127.0.0.1"))).isTrue();
  }
}
