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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Truncates a visitor's address before it is forwarded to the analytics backend (issue #666).
 *
 * <p>Proxying creates a question that direct measurement never asks: the backend would otherwise
 * see this server's address for everyone and count a whole company as one visitor. Sending the full
 * address instead would hand the backend exactly what proxying was meant to withhold.
 *
 * <p>So it goes in truncated — IPv4 to its /24, IPv6 to its /64, which is what Matomo's own
 * anonymisation does and what German data-protection authorities have long treated as the workable
 * middle. Visitors stay countable; individuals do not stay identifiable.
 */
public final class ClientIpFormatter {

  private ClientIpFormatter() {}

  /**
   * The exact address, canonicalised — for {@code tracking.forward_client_ip=full} (issue #712).
   *
   * <p>It parses just as {@link #anonymize} does, and that is the point rather than tidiness. The
   * resolved address is only trustworthy as far as the proxy in front of this server is: behind
   * one, {@code X-Forwarded-For} carries whatever that proxy passed along. Forwarding the raw
   * string would put unvalidated foreign text into an outgoing header, and until now nothing had to
   * say so because truncating an address requires understanding it first.
   */
  public static Optional<String> normalize(String ip) {
    return parse(ip).map(InetAddress::getHostAddress);
  }

  /**
   * The address truncated to its /24 (IPv4) or /64 (IPv6) — the default, and the recommendation.
   */
  public static Optional<String> anonymize(String ip) {
    return parse(ip)
        .flatMap(
            parsed -> {
              byte[] address = parsed.getAddress();
              if (address.length == 4) {
                address[3] = 0;
              } else if (address.length == 16) {
                for (int i = 8; i < 16; i++) {
                  address[i] = 0;
                }
              } else {
                return Optional.empty();
              }
              try {
                return Optional.of(InetAddress.getByAddress(address).getHostAddress());
              } catch (UnknownHostException e) {
                return Optional.empty();
              }
            });
  }

  /**
   * Parses a literal address, or empty when it is missing or unparseable — in which case nothing is
   * forwarded in any mode, because a half-understood address is not worth guessing at.
   */
  private static Optional<InetAddress> parse(String ip) {
    if (ip == null || ip.isBlank()) {
      return Optional.empty();
    }
    String value = ip.trim();
    if (value.startsWith("[") && value.endsWith("]")) {
      value = value.substring(1, value.length() - 1);
    }
    try {
      // Literal only: this must never trigger a DNS lookup on a request path.
      if (!isIpLiteral(value)) {
        return Optional.empty();
      }
      return Optional.of(InetAddress.getByName(value));
    } catch (UnknownHostException e) {
      return Optional.empty();
    }
  }

  private static boolean isIpLiteral(String value) {
    if (value.indexOf(':') >= 0) {
      return true; // IPv6 literal; getByName never resolves a string containing ':'
    }
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) {
      return false;
    }
    for (String part : parts) {
      if (part.isEmpty() || part.length() > 3) {
        return false;
      }
      for (int i = 0; i < part.length(); i++) {
        if (!Character.isDigit(part.charAt(i))) {
          return false;
        }
      }
      if (Integer.parseInt(part) > 255) {
        return false;
      }
    }
    return true;
  }
}
