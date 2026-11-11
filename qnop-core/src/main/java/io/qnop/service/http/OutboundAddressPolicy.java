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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/**
 * Which network addresses an operator-configured outbound request may never reach (issue #904).
 *
 * <p>Pure and DNS-free: it classifies an already-parsed {@link InetAddress}. {@link
 * OutboundUriGuard} applies it to IP literals in a URL; {@link GuardedDnsResolver} applies it to
 * every address a host name resolves to, at connection time.
 *
 * <p>IPv6 forms that carry an IPv4 address (IPv4-mapped, IPv4-compatible, 6to4) are judged by the
 * embedded IPv4 address, and the NAT64 well-known prefix is refused outright, so no IPv6 spelling
 * reaches a destination its IPv4 form could not.
 */
public final class OutboundAddressPolicy {

  /**
   * Cloud metadata / platform endpoints. Most already sit in a blocked range (link-local, CGNAT,
   * ULA); they are listed explicitly so a later change to the ranges cannot re-open them, and
   * because Azure's lives in public address space.
   */
  private static final List<byte[]> METADATA_ADDRESSES =
      List.of(
          v4(169, 254, 169, 254), // AWS / GCP / Azure / OpenStack / Oracle IMDS
          v4(169, 254, 170, 2), // AWS ECS task metadata
          v4(100, 100, 100, 200), // Alibaba Cloud
          v4(168, 63, 129, 16), // Azure WireServer
          v4(192, 0, 0, 192), // Oracle Cloud (legacy)
          v6Fd00Ec2Imds()); // AWS IMDS over IPv6, fd00:ec2::254

  private OutboundAddressPolicy() {}

  /** Whether {@code address} must never be the destination of an operator-configured request. */
  public static boolean isBlocked(InetAddress address) {
    byte[] raw = address.getAddress();
    if (METADATA_ADDRESSES.stream().anyMatch(m -> Arrays.equals(m, raw))) {
      return true;
    }
    if (address instanceof Inet4Address) {
      return isBlockedV4(raw);
    }
    if (address instanceof Inet6Address) {
      return isBlockedV6(raw);
    }
    return true; // an address family this policy does not know is not one it allows
  }

  private static boolean isBlockedV4(byte[] a) {
    int b0 = a[0] & 0xff;
    int b1 = a[1] & 0xff;
    return b0 == 0 // 0.0.0.0/8 "this network"
        || b0 == 10 // 10.0.0.0/8 private
        || b0 == 127 // 127.0.0.0/8 loopback
        || (b0 == 100 && (b1 & 0xc0) == 64) // 100.64.0.0/10 shared address space (CGNAT)
        || (b0 == 169 && b1 == 254) // 169.254.0.0/16 link-local
        || (b0 == 172 && (b1 & 0xf0) == 16) // 172.16.0.0/12 private
        || (b0 == 192 && b1 == 168) // 192.168.0.0/16 private
        || (b0 == 192 && b1 == 0 && (a[2] & 0xff) == 0) // 192.0.0.0/24 IETF protocol assignments
        || (b0 == 198 && (b1 & 0xfe) == 18) // 198.18.0.0/15 benchmarking
        || b0 >= 224; // 224.0.0.0/4 multicast, 240.0.0.0/4 reserved, 255.255.255.255 broadcast
  }

  private static boolean isBlockedV6(byte[] a) {
    if (isZero(a, 0, 10) && (a[10] & 0xff) == 0xff && (a[11] & 0xff) == 0xff) {
      return isBlockedV4(Arrays.copyOfRange(a, 12, 16)); // ::ffff:0:0/96 IPv4-mapped
    }
    if (isZero(a, 0, 12)) {
      // ::/128 unspecified, ::1 loopback, and the deprecated IPv4-compatible ::a.b.c.d
      return isZero(a, 12, 15) || isBlockedV4(Arrays.copyOfRange(a, 12, 16));
    }
    if (a[0] == 0 && a[1] == 0x64 && (a[2] & 0xff) == 0xff && (a[3] & 0xff) == 0x9b) {
      return true; // 64:ff9b::/96 NAT64 well-known prefix (and the 64:ff9b:1::/48 local-use one)
    }
    if (a[0] == 0x20 && a[1] == 0x02) {
      return isBlockedV4(Arrays.copyOfRange(a, 2, 6)); // 2002::/16 6to4 embeds an IPv4 address
    }
    int b0 = a[0] & 0xff;
    int b1 = a[1] & 0xff;
    return (b0 & 0xfe) == 0xfc // fc00::/7 unique local
        || (b0 == 0xfe && (b1 & 0xc0) == 0x80) // fe80::/10 link-local
        || (b0 == 0xfe && (b1 & 0xc0) == 0xc0) // fec0::/10 site-local (deprecated)
        || b0 == 0xff; // ff00::/8 multicast
  }

  private static boolean isZero(byte[] a, int from, int to) {
    for (int i = from; i < to; i++) {
      if (a[i] != 0) {
        return false;
      }
    }
    return true;
  }

  private static byte[] v4(int a, int b, int c, int d) {
    return new byte[] {(byte) a, (byte) b, (byte) c, (byte) d};
  }

  private static byte[] v6Fd00Ec2Imds() {
    byte[] a = new byte[16];
    a[0] = (byte) 0xfd;
    a[2] = 0x0e;
    a[3] = (byte) 0xc2;
    a[14] = 0x02;
    a[15] = 0x54;
    return a;
  }
}
