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
import java.net.UnknownHostException;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.http.HttpException;
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
 *       private network, so it is resolved by the platform resolver and handed to the connection
 *       operator as a pre-resolved address — exempt from {@link OutboundAddressPolicy}, by design
 *       and only for this one host. (The first resolved address is used per route.)
 *   <li><strong>Non-proxy hosts</strong> ({@link HttpClientProperties.Proxy#bypasses}) are routed
 *       directly and keep the full guard: resolved by {@link GuardedDnsResolver}, every address
 *       checked, the socket pinned to the checked address.
 * </ul>
 */
final class OutboundProxyRoutePlanner extends DefaultRoutePlanner {

  private final HttpClientProperties.Proxy proxy;
  private final HostResolver proxyResolver;

  OutboundProxyRoutePlanner(HttpClientProperties.Proxy proxy, HostResolver proxyResolver) {
    super(DefaultSchemePortResolver.INSTANCE);
    if (!proxy.enabled()) {
      throw new IllegalArgumentException("no outbound proxy configured");
    }
    this.proxy = proxy;
    this.proxyResolver = proxyResolver;
  }

  @Override
  protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
    if (proxy.bypasses(target.getHostName())) {
      return null; // direct: GuardedDnsResolver resolves, checks and pins as usual
    }
    InetAddress address;
    try {
      InetAddress[] addresses = proxyResolver.resolve(proxy.host());
      if (addresses == null || addresses.length == 0) {
        throw new UnknownHostException(proxy.host());
      }
      address = addresses[0];
    } catch (UnknownHostException e) {
      throw new HttpException("the configured outbound proxy host cannot be resolved", e);
    }
    // Pre-resolved: the connection operator connects to this address without consulting the
    // guarded DNS resolver — the one intended exemption, for the operator's own proxy.
    return new HttpHost("http", address, proxy.host(), proxy.port());
  }

  /**
   * Re-applies the lexical guard to every request that is about to leave through the proxy, since
   * the connection-time address check cannot see the proxied destination. Direct (non-proxy-host)
   * routes are left to {@link GuardedDnsResolver}.
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
              target.toURI(), "outbound request", true, allowPrivate);
        } catch (IllegalArgumentException e) {
          throw new BlockedOutboundAddressException(target.getHostName());
        }
      }
      return chain.proceed(request, scope);
    };
  }
}
