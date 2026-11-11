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
import java.util.regex.Pattern;

/**
 * The SSRF check for any operator-supplied URL this server will fetch.
 *
 * <p>Extracted from the OIDC discovery guard (issue #21) when usage tracking gained a second one
 * (issue #666): two features that let an administrator name a URL for the server to call are two
 * places the same mistake can be made, and duplicating a security check is how the copies drift
 * apart.
 *
 * <p><strong>Two halves (issue #904, ADR-0056 amendment).</strong> This class is the
 * <em>lexical</em> half, run when a URL is saved and again before use: http(s) only, a host is
 * present, no reserved name ({@code localhost}, {@code *.local}, {@code *.internal}), no
 * non-canonical numeric host (a single integer, hex or octal parts, a short dotted form), and no IP
 * literal in a range {@link OutboundAddressPolicy} blocks. It deliberately performs no DNS: a name
 * resolved here could resolve differently a moment later. Host names are checked by the
 * <em>connection-time</em> half, {@link GuardedDnsResolver}, which resolves once, validates every
 * address and hands exactly those addresses to the socket — see {@link OutboundHttpClients}.
 *
 * <p>Whether private destinations are allowed at all is a <em>deployment</em> decision (a property,
 * not a database setting) — an internal IdP and a self-hosted analytics server are both legitimate,
 * and neither should be reachable by an administrator flipping a switch in the web UI. Malformed
 * numeric hosts are refused even then: no legitimate configuration spells an address that way.
 */
public final class OutboundUriGuard {

  /** A label that a URL parser could read as (part of) a number: decimal, octal or hex. */
  private static final Pattern NUMERIC_LABEL = Pattern.compile("(0x[0-9a-f]*|[0-9]+)");

  /** One canonical decimal octet: 0-255 without leading zeros. */
  private static final Pattern CANONICAL_OCTET =
      Pattern.compile("(0|[1-9][0-9]?|1[0-9]{2}|2[0-4][0-9]|25[0-5])");

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
    String normalized = normalize(host);
    if (isNonCanonicalNumericHost(normalized)) {
      throw new IllegalArgumentException(fieldName + " must not use a non-canonical numeric host");
    }
    if (allowPrivate) {
      return;
    }
    if (isBlockedHost(normalized)) {
      throw new IllegalArgumentException(
          fieldName + " targets a blocked (private/loopback/metadata) host");
    }
  }

  /** Lower-cases, strips IPv6 brackets and a single trailing root dot. */
  private static String normalize(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    if (h.startsWith("[") && h.endsWith("]")) {
      return h.substring(1, h.length() - 1);
    }
    return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
  }

  /**
   * A host whose last label is numeric is an IPv4 address to a URL parser (WHATWG "ends in a
   * number"), and many accept {@code 2130706433}, {@code 0x7f.1} or {@code 0177.0.0.1} for {@code
   * 127.0.0.1}. Only the canonical dotted-quad spelling is accepted.
   */
  static boolean isNonCanonicalNumericHost(String host) {
    if (host.indexOf(':') >= 0) {
      return false; // IPv6 literal — the JDK parser accepts only valid IPv6 text
    }
    String[] labels = host.split("\\.", -1);
    if (!NUMERIC_LABEL.matcher(labels[labels.length - 1]).matches()) {
      return false; // an ordinary host name
    }
    if (labels.length != 4) {
      return true;
    }
    for (String label : labels) {
      if (!CANONICAL_OCTET.matcher(label).matches()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isBlockedHost(String h) {
    if (h.equals("localhost")
        || h.endsWith(".localhost")
        || h.equals("local")
        || h.endsWith(".local")
        || h.equals("internal")
        || h.endsWith(".internal")) {
      return true;
    }
    InetAddress ip = ipLiteralOrNull(h);
    if (ip == null) {
      return false; // a host name — checked at connection time by GuardedDnsResolver
    }
    return OutboundAddressPolicy.isBlocked(ip);
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
      if (!CANONICAL_OCTET.matcher(parts[i]).matches()) {
        return null;
      }
      addr[i] = (byte) Integer.parseInt(parts[i]);
    }
    try {
      return InetAddress.getByAddress(addr); // from raw bytes — never resolves DNS
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
