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

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Builds the HTTP clients for operator-configured destinations — OIDC providers and the
 * usage-tracking backend (issue #904, ADR-0056 amendment).
 *
 * <p>Each client:
 *
 * <ul>
 *   <li>resolves through a {@link GuardedDnsResolver}, so the address validated is the address
 *       connected to (no DNS-rebinding window);
 *   <li>never follows redirects — a 3xx is returned to the caller as-is, so a redirect cannot lead
 *       to a destination nobody validated;
 *   <li>ignores proxy system properties — a proxy would resolve the name itself, outside the guard;
 *   <li>carries the configured connect/read timeouts.
 * </ul>
 */
public final class OutboundHttpClients {

  private OutboundHttpClients() {}

  /** A guarded Apache client using the platform resolver. */
  public static CloseableHttpClient create(HttpClientProperties properties, boolean allowPrivate) {
    return create(properties, allowPrivate, HostResolver.SYSTEM);
  }

  /** A guarded Apache client resolving through {@code resolver} (tests inject one). */
  public static CloseableHttpClient create(
      HttpClientProperties properties, boolean allowPrivate, HostResolver resolver) {
    Timeout connect = Timeout.of(properties.outboundConnectTimeout());
    Timeout read = Timeout.of(properties.outboundReadTimeout());
    return HttpClients.custom()
        .setConnectionManager(
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new GuardedDnsResolver(resolver, allowPrivate))
                .setDefaultConnectionConfig(
                    ConnectionConfig.custom()
                        .setConnectTimeout(connect)
                        .setSocketTimeout(read)
                        .build())
                .build())
        .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(read).build())
        .disableRedirectHandling()
        .disableAutomaticRetries()
        .disableCookieManagement()
        .build();
  }

  /**
   * A Spring request factory over a guarded client, for {@code RestClient}/{@code RestTemplate}.
   */
  public static ClientHttpRequestFactory requestFactory(
      HttpClientProperties properties, boolean allowPrivate) {
    return new HttpComponentsClientHttpRequestFactory(create(properties, allowPrivate));
  }

  /**
   * As {@link #requestFactory(HttpClientProperties, boolean)}, resolving through {@code resolver}.
   */
  public static ClientHttpRequestFactory requestFactory(
      HttpClientProperties properties, boolean allowPrivate, HostResolver resolver) {
    return new HttpComponentsClientHttpRequestFactory(create(properties, allowPrivate, resolver));
  }
}
