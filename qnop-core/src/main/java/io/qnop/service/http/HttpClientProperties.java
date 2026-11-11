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

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable timeouts for the outbound HTTP paths (issue #342), so a slow or hung dependency can
 * never pin a thread indefinitely. Every value is overridable via {@code qnop.http-client.*} (or
 * the matching {@code QNOP_HTTP_CLIENT_*} environment variable); the defaults below apply only when
 * a value is not supplied.
 *
 * <ul>
 *   <li>{@code outbound*} — the connect/read timeouts for HTTP clients qnop makes calls through:
 *       the GitHub email fetch (OIDC login), the SSRF-guarded clients of {@link
 *       OutboundHttpClients} (OIDC discovery, token, user-info and JWK-set requests, and the
 *       usage-tracking proxy, issue #904) and, as JDK {@code HttpURLConnection} defaults, any
 *       remaining legacy client.
 *   <li>{@code smtp*} — the SMTP transport connect/read/write timeouts for outgoing mail.
 *   <li>{@code proxy.*} — the explicit outbound proxy opt-in for the guarded clients (issue #940,
 *       ADR-0056 amendment); unset by default, which means direct connections.
 * </ul>
 *
 * @param outboundConnectTimeout TCP-connect ceiling for outbound HTTP (default 5s)
 * @param outboundReadTimeout response-read ceiling for outbound HTTP (default 15s)
 * @param smtpConnectTimeout SMTP connect ceiling (default 10s)
 * @param smtpReadTimeout SMTP read ceiling (default 30s)
 * @param smtpWriteTimeout SMTP write ceiling (default 30s)
 * @param proxy the outbound proxy for the guarded clients (default: none, direct connections)
 */
@ConfigurationProperties(prefix = "qnop.http-client")
public record HttpClientProperties(
    Duration outboundConnectTimeout,
    Duration outboundReadTimeout,
    Duration smtpConnectTimeout,
    Duration smtpReadTimeout,
    Duration smtpWriteTimeout,
    Proxy proxy) {

  public HttpClientProperties {
    outboundConnectTimeout = orDefault(outboundConnectTimeout, Duration.ofSeconds(5));
    outboundReadTimeout = orDefault(outboundReadTimeout, Duration.ofSeconds(15));
    smtpConnectTimeout = orDefault(smtpConnectTimeout, Duration.ofSeconds(10));
    smtpReadTimeout = orDefault(smtpReadTimeout, Duration.ofSeconds(30));
    smtpWriteTimeout = orDefault(smtpWriteTimeout, Duration.ofSeconds(30));
    proxy = proxy == null ? Proxy.NONE : proxy;
  }

  private static Duration orDefault(Duration value, Duration fallback) {
    return value == null ? fallback : value;
  }

  /**
   * The explicit outbound HTTP proxy for the SSRF-guarded clients (issue #940, ADR-0056 amendment)
   * — {@code qnop.http-client.proxy.*}.
   *
   * <p>A <em>deployment</em> property only: it is never an application setting, so nobody can route
   * the guarded clients through a host of their choosing from the web UI. The JVM proxy system
   * properties ({@code http.proxyHost}, {@code https.proxyHost}, {@code http.nonProxyHosts}) stay
   * ignored; there is no implicit opt-in.
   *
   * <p>Unset {@code host} means direct connections (the default). When set, {@code port} is
   * required. The proxy is spoken to over plain HTTP ({@code CONNECT} for https destinations);
   * proxy authentication is not supported.
   *
   * @param host the proxy host name or IP literal; blank/unset disables proxy mode
   * @param port the proxy port (1–65535), required with {@code host}
   * @param nonProxyHosts destinations that bypass the proxy and keep the full direct, address-
   *     pinned guard: an exact host name ({@code idp.corp.example}), a leading-wildcard suffix
   *     ({@code *.corp.example}, matching any subdomain) or an IPv6 literal; case-insensitive, a
   *     trailing root dot is ignored; no ports; must not cover the proxy host itself
   */
  public record Proxy(String host, Integer port, List<String> nonProxyHosts) {

    /** No proxy: the guarded clients connect directly. */
    public static final Proxy NONE = new Proxy(null, null, null);

    /** A host name or IP literal (IPv6 without brackets); no scheme, port, path or credentials. */
    private static final Pattern HOST =
        Pattern.compile("[A-Za-z0-9.-]+|[0-9A-Fa-f.]*:[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*");

    /**
     * A normalised non-proxy entry: an exact host name, {@code *.} followed by a domain suffix, or
     * an IPv6 literal. No port, no empty label: an entry that could never match is refused rather
     * than silently proxying the destination it was meant to keep direct.
     */
    private static final Pattern NON_PROXY_ENTRY =
        Pattern.compile("(\\*\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*|[0-9a-f.]*:[0-9a-f.]*:[0-9a-f:.]*");

    public Proxy {
      host = host == null || host.isBlank() ? null : host.trim();
      nonProxyHosts =
          nonProxyHosts == null
              ? List.of()
              : nonProxyHosts.stream()
                  .filter(entry -> entry != null && !entry.isBlank())
                  .map(entry -> normalize(entry.trim()))
                  .toList();
      validate(host, port, nonProxyHosts);
    }

    private static void validate(String host, Integer port, List<String> nonProxyHosts) {
      if (host == null) {
        if (port != null || !nonProxyHosts.isEmpty()) {
          throw new IllegalArgumentException(
              "qnop.http-client.proxy.port/non-proxy-hosts are set but"
                  + " qnop.http-client.proxy.host is not");
        }
        return;
      }
      if (!HOST.matcher(host).matches()) {
        throw new IllegalArgumentException(
            "qnop.http-client.proxy.host must be a bare host name or IP literal"
                + " (no scheme, port, path or credentials)");
      }
      if (port == null || port < 1 || port > 65535) {
        throw new IllegalArgumentException(
            "qnop.http-client.proxy.port must be set to 1-65535 when"
                + " qnop.http-client.proxy.host is set");
      }
      for (String entry : nonProxyHosts) {
        if (!NON_PROXY_ENTRY.matcher(entry).matches()) {
          throw new IllegalArgumentException(
              "qnop.http-client.proxy.non-proxy-hosts entry is not a host name, *.suffix or"
                  + " IPv6 literal (no port): "
                  + entry);
        }
      }
      // The proxy host is exempt from the address check only on the proxy hop (see
      // OutboundProxyRoutePlanner); a non-proxy entry covering it would open a direct route to it.
      if (bypasses(nonProxyHosts, host)) {
        throw new IllegalArgumentException(
            "qnop.http-client.proxy.non-proxy-hosts must not cover the proxy host itself");
      }
    }

    /** Whether proxy mode is active. */
    public boolean enabled() {
      return host != null;
    }

    /**
     * Whether {@code targetHost} bypasses the proxy (and therefore keeps the full direct guard).
     */
    public boolean bypasses(String targetHost) {
      return bypasses(nonProxyHosts, targetHost);
    }

    /** Whether {@code candidate} names the configured proxy host (normalised comparison). */
    public boolean isProxyHost(String candidate) {
      return host != null && candidate != null && normalize(candidate).equals(normalize(host));
    }

    private static boolean bypasses(List<String> entries, String targetHost) {
      if (targetHost == null) {
        return false;
      }
      String target = normalize(targetHost);
      for (String entry : entries) {
        if (entry.startsWith("*.") ? target.endsWith(entry.substring(1)) : target.equals(entry)) {
          return true;
        }
      }
      return false;
    }

    /**
     * Lower-cases and strips IPv6 brackets and a single trailing root dot. Applied to entries and
     * targets alike, so {@code IDP.Corp.example.} and {@code idp.corp.example} are one host.
     */
    private static String normalize(String host) {
      String h = host.toLowerCase(Locale.ROOT);
      if (h.startsWith("[") && h.endsWith("]")) {
        h = h.substring(1, h.length() - 1);
      }
      return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }
  }
}
