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
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;

/**
 * The connection-time half of the SSRF guard (issue #904, ADR-0056 amendment).
 *
 * <p>Apache HttpClient asks this resolver for the addresses of the request's host and then opens
 * the socket to <em>those</em> addresses — there is no second lookup between the check and the
 * connect, so a name that answers with a public address first and a private one later cannot slip
 * through. Every resolved address must pass {@link OutboundAddressPolicy}; one blocked address
 * refuses the whole host (a round-robin answer must not leave the choice to chance).
 *
 * <p>TLS is unaffected: the client still uses the host <em>name</em> for SNI and certificate
 * verification, and sends it as the {@code Host} header.
 *
 * <p>With {@code allowPrivate} (the deployment-level opt-in) the addresses are passed through
 * unchanged.
 */
public final class GuardedDnsResolver implements DnsResolver {

  private final HostResolver delegate;
  private final boolean allowPrivate;

  public GuardedDnsResolver(HostResolver delegate, boolean allowPrivate) {
    this.delegate = delegate;
    this.allowPrivate = allowPrivate;
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    InetAddress[] addresses = delegate.resolve(host);
    if (addresses == null || addresses.length == 0) {
      throw new UnknownHostException(host);
    }
    if (!allowPrivate && Arrays.stream(addresses).anyMatch(OutboundAddressPolicy::isBlocked)) {
      throw new BlockedOutboundAddressException(host);
    }
    return addresses.clone();
  }

  @Override
  public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
    return Arrays.stream(resolve(host)).map(a -> new InetSocketAddress(a, port)).toList();
  }

  @Override
  public String resolveCanonicalHostname(String host) throws UnknownHostException {
    return host; // never consulted for routing; avoid a reverse lookup that could disagree
  }
}
