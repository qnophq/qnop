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

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * Resolves the client IP for rate-limit keying and usage tracking (issue #18, ADR-0027), gating
 * {@code X-Forwarded-For} behind a configurable trusted-proxy allow-list so the header cannot be
 * used to rotate per-IP buckets.
 *
 * <p>Resolution rules:
 *
 * <ol>
 *   <li>No / blank {@code X-Forwarded-For} → {@code request.getRemoteAddr()}.
 *   <li>Empty trusted-proxy list → no proxy is trusted; ignore the header and use {@code
 *       getRemoteAddr()}. This is the secure default for a server with no reverse proxy.
 *   <li>The immediate hop ({@code getRemoteAddr()}) is not in the trust list → the header is
 *       client-controlled; use {@code getRemoteAddr()}.
 *   <li>Otherwise walk the header <b>right to left</b> (issue #898): an appending proxy writes the
 *       peer it saw on the right, so only entries to the right of the first untrusted hop were
 *       written by infrastructure we trust. Trusted hops are skipped; the first entry outside the
 *       trust list is the client. If every entry is trusted, the leftmost one is returned.
 *   <li>An entry that is not an IP literal (empty, {@code unknown}, a hostname, …) ends the walk:
 *       nothing to its left can be verified, so the last trusted hop reached is returned. Entries
 *       are never resolved via DNS.
 * </ol>
 *
 * <p>Several {@code X-Forwarded-For} header lines are joined in order, as RFC 7230 §3.2.2 allows.
 * Operators behind a reverse proxy MUST set {@code qnop.auth.rate-limit.trusted-proxy-cidrs} to the
 * egress addresses of every proxy hop (see {@code docs/DEPLOYMENT.md}).
 */
@Component
public class HttpClientIpResolver {

  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  private static final Pattern IPV4 =
      Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::\\d{1,5})?");
  private static final Pattern BRACKETED_IPV6 =
      Pattern.compile("\\[([0-9A-Fa-f:.]+)](?::\\d{1,5})?");
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

  private static final Logger log = LoggerFactory.getLogger(HttpClientIpResolver.class);

  private final List<IpAddressMatcher> trustedProxyMatchers;

  public HttpClientIpResolver(RateLimitProperties properties) {
    this.trustedProxyMatchers =
        properties.trustedProxyCidrs().stream()
            .map(String::trim)
            .map(IpAddressMatcher::new)
            .toList();
    if (trustedProxyMatchers.isEmpty()) {
      log.warn(
          "Rate limiting is active but qnop.auth.rate-limit.trusted-proxy-cidrs is empty:"
              + " X-Forwarded-For is ignored and the connection IP is used. This is the secure"
              + " default for a direct deployment. If qnop runs behind a reverse proxy, set it to"
              + " the proxy's egress CIDRs, or every client shares one per-IP bucket and the limit"
              + " is effectively bypassable.");
    }
  }

  /** Returns the best-trusted client IP for the request. */
  public String resolve(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    if (trustedProxyMatchers.isEmpty() || !isTrusted(remoteAddr)) {
      return remoteAddr;
    }
    List<String> entries = forwardedEntries(request);
    String lastTrusted = remoteAddr;
    for (int i = entries.size() - 1; i >= 0; i--) {
      Optional<String> address = parseLiteral(entries.get(i));
      if (address.isEmpty()) {
        return lastTrusted;
      }
      if (!isTrusted(address.get())) {
        return address.get();
      }
      lastTrusted = address.get();
    }
    return lastTrusted;
  }

  private boolean isTrusted(String address) {
    return trustedProxyMatchers.stream().anyMatch(matcher -> matcher.matches(address));
  }

  /** All comma-separated entries of every {@code X-Forwarded-For} line, left to right. */
  private static List<String> forwardedEntries(HttpServletRequest request) {
    Enumeration<String> lines = request.getHeaders(X_FORWARDED_FOR);
    if (lines == null) {
      return List.of();
    }
    List<String> entries = new ArrayList<>();
    for (String line : Collections.list(lines)) {
      if (line == null || line.isBlank()) {
        continue;
      }
      for (String entry : line.split(",", -1)) {
        entries.add(entry.trim());
      }
    }
    return entries;
  }

  /**
   * The canonical form of an IP literal (optionally with a port, IPv6 optionally bracketed), or
   * empty when the entry is anything else. Never performs a DNS lookup: only strings that are
   * already numeric literals reach {@link InetAddress}.
   */
  static Optional<String> parseLiteral(String entry) {
    Matcher v4 = IPV4.matcher(entry);
    if (v4.matches()) {
      byte[] bytes = new byte[4];
      for (int i = 0; i < 4; i++) {
        int octet = Integer.parseInt(v4.group(i + 1));
        if (octet > 255) {
          return Optional.empty();
        }
        bytes[i] = (byte) octet;
      }
      return toHostAddress(bytes);
    }
    Matcher bracketed = BRACKETED_IPV6.matcher(entry);
    String v6 = bracketed.matches() ? bracketed.group(1) : entry;
    if (!IPV6.matcher(v6).matches()) {
      return Optional.empty();
    }
    try {
      // Contains ':' and only hex digits / ':' / '.', so the JDK parses it as an IPv6 literal (or
      // rejects it) without consulting a resolver.
      return Optional.of(InetAddress.getByName(v6).getHostAddress());
    } catch (UnknownHostException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static Optional<String> toHostAddress(byte[] bytes) {
    try {
      return Optional.of(InetAddress.getByAddress(bytes).getHostAddress());
    } catch (UnknownHostException e) {
      return Optional.empty();
    }
  }
}
