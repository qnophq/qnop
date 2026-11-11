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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Groups client addresses for rate-limit keying (issues #898/#899). A single IPv6 address is
 * meaningless as a key: one subscriber is usually handed a whole /56 (often more), so keying per
 * /128 lets one party rotate through more buckets than anyone can count. IPv6 is therefore grouped
 * by its /56 everywhere; IPv4 is kept per address for the per-IP filters and grouped by /24 where
 * the key is "the network an attempt comes from".
 */
final class ClientNetworks {

  private static final int IPV6_PREFIX_BYTES = 7; // /56

  private ClientNetworks() {}

  /**
   * Key for the per-IP filters: the IPv4 address itself, or the IPv6 /56 it lies in. Anything that
   * is not an IP literal is used as is.
   */
  static String perIpKey(String clientIp) {
    return bytes(clientIp)
        .map(b -> b.length == 4 ? hostAddress(clientIp) : prefix(b, IPV6_PREFIX_BYTES))
        .orElse(clientIp);
  }

  /**
   * Key for "the network an attempt comes from": the IPv4 /24 or the IPv6 /56. Anything that is not
   * an IP literal is used as is, marked so it cannot collide with a prefix.
   */
  static String networkKey(String clientIp) {
    return bytes(clientIp)
        .map(b -> prefix(b, b.length == 4 ? 3 : IPV6_PREFIX_BYTES))
        .orElse("raw:" + clientIp);
  }

  private static Optional<byte[]> bytes(String clientIp) {
    return HttpClientIpResolver.parseLiteral(clientIp == null ? "" : clientIp)
        .flatMap(
            canonical -> {
              try {
                // A canonical literal: InetAddress parses it without any lookup.
                return Optional.of(InetAddress.getByName(canonical).getAddress());
              } catch (UnknownHostException e) {
                return Optional.empty();
              }
            });
  }

  private static String hostAddress(String clientIp) {
    return HttpClientIpResolver.parseLiteral(clientIp).orElse(clientIp);
  }

  private static String prefix(byte[] address, int keepBytes) {
    return HexFormat.of().formatHex(address, 0, keepBytes) + "/" + keepBytes * 8;
  }
}
