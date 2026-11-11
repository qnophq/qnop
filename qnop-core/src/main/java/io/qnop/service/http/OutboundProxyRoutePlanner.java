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
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;

/**
 * Routes a guarded client through the deployment's explicit outbound proxy (issue #940, ADR-0056
 * amendment) — the weaker of the two transport modes, chosen only by deployment configuration.
 *
 * <p>What changes in proxy mode, deliberately:
 *
 * <ul>
 *   <li><strong>Proxied destinations</strong> are never resolved here — the proxy resolves them and
 *       opens the connection, so the connection-time address check ({@link GuardedDnsResolver})
 *       <em>cannot</em> apply to them. {@link #lexicalGuard} re-runs the lexical check ({@link
 *       OutboundUriGuard}) on every proxied request instead, and the proxy's own egress ACL is the
 *       remaining control.
 *   <li><strong>The proxy host itself</strong> is operator configuration and usually lives on a
 *       private network, so {@link #exemptingProxyHost} lets exactly that name past {@link
 *       OutboundAddressPolicy}. It is resolved like any other connection target — once per new
 *       connection, not per request (pooled connections are reused), with every address it answers
 *       tried in turn by the connection operator. The exemption is by name and reaches only the
 *       proxy hop: a proxied destination is never resolved locally, and configuration refuses a
 *       non-proxy entry that covers the proxy host, so no direct route can lead to it.
 *   <li><strong>Non-proxy hosts</strong> ({@link HttpClientProperties.Proxy#bypasses}) are routed
 *       directly and keep the full guard: resolved by {@link GuardedDnsResolver}, every address
 *       checked, the socket pinned to the checked address.
 * </ul>
 */
final class OutboundProxyRoutePlanner extends DefaultRoutePlanner {

  private final HttpHost proxyHost;
  private final HttpClientProperties.Proxy proxy;

  OutboundProxyRoutePlanner(HttpClientProperties.Proxy proxy) {
    super(DefaultSchemePortResolver.INSTANCE);
    if (!proxy.enabled()) {
      throw new IllegalArgumentException("no outbound proxy configured");
    }
    this.proxy = proxy;
    this.proxyHost = new HttpHost("http", proxy.host(), proxy.port());
  }

  @Override
  protected HttpHost determineProxy(HttpHost target, HttpContext context) {
    // null = direct: GuardedDnsResolver resolves, checks and pins as usual
    return proxy.bypasses(target.getHostName()) ? null : proxyHost;
  }

  /**
   * The DNS resolver for a proxy-mode client: the configured proxy host resolves through {@code
   * proxyResolver} unchecked; every other name goes through {@code guarded}.
   */
  static DnsResolver exemptingProxyHost(
      HttpClientProperties.Proxy proxy, DnsResolver guarded, HostResolver proxyResolver) {
    return new DnsResolver() {
      @Override
      public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!proxy.isProxyHost(host)) {
          return guarded.resolve(host);
        }
        InetAddress[] addresses = proxyResolver.resolve(host);
        if (addresses == null || addresses.length == 0) {
          throw new UnknownHostException(host);
        }
        return addresses.clone();
      }

      @Override
      public String resolveCanonicalHostname(String host) {
        return host; // never consulted for routing; avoid a reverse lookup
      }
    };
  }

  /**
   * Re-applies the lexical guard to every request that is about to leave through the proxy, since
   * the connection-time address check cannot see the proxied destination. Runs before the
   * connection (and any {@code CONNECT}) is opened. Direct (non-proxy-host) routes are left to
   * {@link GuardedDnsResolver}.
   *
   * @param allowPrivate the deployment's private-destination opt-in for this client
   */
  static ExecChainHandler lexicalGuard(boolean allowPrivate) {
    return (request, scope, chain) -> {
      HttpRoute route = scope.route;
      if (route.getProxyHost() != null) {
        HttpHost target = route.getTargetHost();
        try {
          OutboundUriGuard.requireAllowedHttpUri(
              targetUri(target), "outbound request", true, allowPrivate);
        } catch (IllegalArgumentException | URISyntaxException e) {
          throw new BlockedOutboundAddressException(target.getHostName());
        }
      }
      return chain.proceed(request, scope);
    };
  }

  /** The target as a URI string; the multi-argument constructor brackets an IPv6 literal. */
  private static String targetUri(HttpHost target) throws URISyntaxException {
    String host = target.getHostName();
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    return new URI(target.getSchemeName(), null, host, target.getPort(), null, null, null)
        .toString();
  }
}
