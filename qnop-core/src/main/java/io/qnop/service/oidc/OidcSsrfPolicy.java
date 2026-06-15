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
package io.qnop.service.oidc;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for operator-supplied OIDC URIs (issue #21). Validates scheme (http/https) and, by
 * default, blocks requests to private/loopback/link-local/metadata destinations before any server
 * fetch (e.g. discovery) happens.
 *
 * <p><strong>DNS-free by design.</strong> Only <em>IP literals</em> in the host are range-checked;
 * hostnames are never resolved here (resolving would add a TOCTOU window and a DNS-rebinding
 * angle). A hostname that is not an IP literal is allowed through unless it is on the static name
 * blocklist (e.g. {@code localhost}); the residual risk of a public hostname resolving to a private
 * address is accepted (and mitigated operationally).
 *
 * <p>The block can be relaxed for trusted internal IdPs with {@code
 * qnop.auth.oidc.allow-private-discovery-uris=true} (scheme validation still applies).
 */
@Component
public class OidcSsrfPolicy {

  private final boolean allowPrivate;

  public OidcSsrfPolicy(
      @Value("${qnop.auth.oidc.allow-private-discovery-uris:false}") boolean allowPrivate) {
    this.allowPrivate = allowPrivate;
  }

  /**
   * Requires {@code value} to be a syntactically valid http(s) URI whose host is not a blocked
   * (private/loopback/metadata) destination. A blank value is accepted only when {@code !required}.
   *
   * @throws IllegalArgumentException if the value is missing (when required), malformed, not
   *     http(s), or targets a blocked host.
   */
  public void requirePublicHttpUri(String value, String fieldName, boolean required) {
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

  private boolean isBlockedHost(String host) {
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

  /**
   * Parses {@code host} as an IP literal without any DNS resolution; null if it is not a literal.
   */
  private InetAddress ipLiteralOrNull(String host) {
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

  private boolean isUniqueLocalIpv6(InetAddress ip) {
    byte[] a = ip.getAddress();
    // fc00::/7 (unique local addresses) — first 7 bits are 1111110.
    return a.length == 16 && (a[0] & 0xfe) == 0xfc;
  }
}
