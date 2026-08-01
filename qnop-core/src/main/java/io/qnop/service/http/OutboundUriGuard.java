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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * The SSRF check for any operator-supplied URL this server will fetch.
 *
 * <p>Extracted from the OIDC discovery guard (issue #21) when usage tracking gained a second one
 * (issue #666): two features that let an administrator name a URL for the server to call are two
 * places the same mistake can be made, and duplicating a security check is how the copies drift
 * apart.
 *
 * <p><strong>DNS-free by design.</strong> Only <em>IP literals</em> are range-checked; hostnames
 * are never resolved here, because resolving would add a TOCTOU window and a DNS-rebinding angle. A
 * hostname that is not a literal passes unless it is on the static name blocklist; the residual
 * risk of a public name resolving to a private address is accepted and mitigated operationally.
 *
 * <p>Whether private destinations are allowed at all is a <em>deployment</em> decision (a property,
 * not a database setting) — an internal IdP and a self-hosted analytics server are both legitimate,
 * and neither should be reachable by an administrator flipping a switch in the web UI.
 */
public final class OutboundUriGuard {

  private OutboundUriGuard() {}

  /**
   * Requires {@code value} to be a syntactically valid http(s) URI whose host is not a blocked
   * (private/loopback/metadata) destination.
   *
   * @throws IllegalArgumentException if the value is missing (when required), malformed, not
   *     http(s), or targets a blocked host
   */
  public static void requireAllowedHttpUri(
      String value, String fieldName, boolean required, boolean allowPrivate) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      if (required) {
        throw new IllegalArgumentException(fieldName + " is required");
      }
      return;
    }
    URI uri;
    try {
      uri = URI.create(trimmed);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(fieldName + " is not a valid URI");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
      throw new IllegalArgumentException(fieldName + " must be an http(s) URL");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must include a host");
    }
    if (allowPrivate) {
      return;
    }
    if (isBlockedHost(host)) {
      throw new IllegalArgumentException(
          fieldName + " targets a blocked (private/loopback/metadata) host");
    }
  }

  private static boolean isBlockedHost(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    if (h.startsWith("[") && h.endsWith("]")) {
      h = h.substring(1, h.length() - 1);
    }
    if (h.equals("localhost")
        || h.endsWith(".localhost")
        || h.endsWith(".local")
        || h.endsWith(".internal")) {
      return true;
    }
    InetAddress ip = ipLiteralOrNull(h);
    if (ip == null) {
      return false; // a (non-literal) hostname — not resolved here (DNS-free)
    }
    return ip.isLoopbackAddress()
        || ip.isAnyLocalAddress()
        || ip.isLinkLocalAddress()
        || ip.isSiteLocalAddress()
        || isUniqueLocalIpv6(ip);
  }

  /** Parses {@code host} as an IP literal without any DNS resolution; null if it is not one. */
  private static InetAddress ipLiteralOrNull(String host) {
    if (host.indexOf(':') >= 0) {
      // IPv6 literal — getByName never performs DNS for a string containing ':'.
      try {
        return InetAddress.getByName(host);
      } catch (UnknownHostException e) {
        return null;
      }
    }
    String[] parts = host.split("\\.");
    if (parts.length != 4) {
      return null;
    }
    byte[] addr = new byte[4];
    for (int i = 0; i < 4; i++) {
      try {
        int octet = Integer.parseInt(parts[i]);
        if (octet < 0 || octet > 255) {
          return null;
        }
        addr[i] = (byte) octet;
      } catch (NumberFormatException e) {
        return null;
      }
    }
    try {
      return InetAddress.getByAddress(addr); // from raw bytes — never resolves DNS
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private static boolean isUniqueLocalIpv6(InetAddress ip) {
    byte[] a = ip.getAddress();
    // fc00::/7 (unique local addresses) — first 7 bits are 1111110.
    return a.length == 16 && (a[0] & 0xFE) == 0xFC;
  }
}
