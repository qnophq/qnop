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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
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
 *   <li>ignores proxy system properties — a proxy would resolve the name itself, outside the guard.
 *       Only the explicit deployment opt-in {@code qnop.http-client.proxy.*} routes through a proxy
 *       (issue #940), and then under the weaker model documented on {@link
 *       OutboundProxyRoutePlanner};
 *   <li>is bounded in time: the configured connect/read timeouts, and a connection-pool lease wait
 *       no longer than the connect timeout, so a slow backend cannot queue request threads behind a
 *       full pool;
 *   <li>is bounded in size: no transparent decompression, and at most {@link #MAX_RESPONSE_BYTES}
 *       of response body.
 * </ul>
 */
public final class OutboundHttpClients {

  /** Pooled connections per destination; generous for an IdP or an analytics backend. */
  public static final int MAX_CONNECTIONS_PER_ROUTE = 20;

  /** Pooled connections per client, across destinations. */
  public static final int MAX_CONNECTIONS_TOTAL = 50;

  /**
   * The largest response body read from an operator-configured destination (1 MiB). Discovery
   * documents, JWK sets, user-info and token responses, and analytics scripts are all far smaller.
   */
  public static final int MAX_RESPONSE_BYTES = 1024 * 1024;

  private OutboundHttpClients() {}

  /** A guarded Apache client using the platform resolver. */
  public static CloseableHttpClient create(HttpClientProperties properties, boolean allowPrivate) {
    return create(properties, allowPrivate, HostResolver.SYSTEM);
  }

  /** A guarded Apache client resolving through {@code resolver} (tests inject one). */
  public static CloseableHttpClient create(
      HttpClientProperties properties, boolean allowPrivate, HostResolver resolver) {
    return create(properties, allowPrivate, resolver, HostResolver.SYSTEM);
  }

  /**
   * A guarded Apache client resolving destinations through {@code resolver} and, in proxy mode, the
   * proxy host through {@code proxyResolver} (tests inject both).
   */
  static CloseableHttpClient create(
      HttpClientProperties properties,
      boolean allowPrivate,
      HostResolver resolver,
      HostResolver proxyResolver) {
    HttpClientBuilder builder = baseBuilder(properties, allowPrivate, resolver);
    HttpClientProperties.Proxy proxy = properties.proxy();
    if (proxy.enabled()) {
      // Proxy mode (issue #940): the weaker trust model is explicit here, not accidental — see
      // OutboundProxyRoutePlanner. Proxied requests get the lexical guard per request; non-proxy
      // hosts stay direct and keep the connection-time guard of GuardedDnsResolver.
      builder
          .setRoutePlanner(new OutboundProxyRoutePlanner(proxy, proxyResolver))
          .addExecInterceptorFirst(
              "proxy-lexical-guard", OutboundProxyRoutePlanner.lexicalGuard(allowPrivate));
    }
    return builder.build();
  }

  private static HttpClientBuilder baseBuilder(
      HttpClientProperties properties, boolean allowPrivate, HostResolver resolver) {
    return HttpClients.custom()
        .setConnectionManager(
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new GuardedDnsResolver(resolver, allowPrivate))
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .setMaxConnTotal(MAX_CONNECTIONS_TOTAL)
                .setDefaultConnectionConfig(
                    ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.outboundConnectTimeout()))
                        .setSocketTimeout(Timeout.of(properties.outboundReadTimeout()))
                        .build())
                .build())
        .setDefaultRequestConfig(defaultRequestConfig(properties))
        .addExecInterceptorFirst("response-size-limit", limitResponseSize(MAX_RESPONSE_BYTES))
        .disableContentCompression()
        .disableRedirectHandling()
        .disableAutomaticRetries()
        .disableCookieManagement();
  }

  /**
   * The request defaults every guarded client carries. A per-request {@code RequestConfig}
   * <em>replaces</em> the client default in HttpClient 5, so callers that need their own must start
   * from {@code RequestConfig.copy(defaultRequestConfig(...))}.
   */
  public static RequestConfig defaultRequestConfig(HttpClientProperties properties) {
    return RequestConfig.custom()
        .setConnectionRequestTimeout(Timeout.of(properties.outboundConnectTimeout()))
        .setResponseTimeout(Timeout.of(properties.outboundReadTimeout()))
        .setRedirectsEnabled(false)
        .build();
  }

  /**
   * A Spring request factory over a guarded client, for {@code RestClient}/{@code RestTemplate}.
   */
  public static HttpComponentsClientHttpRequestFactory requestFactory(
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

  /** Wraps every response body so that reading more than {@code maxBytes} fails. */
  static ExecChainHandler limitResponseSize(int maxBytes) {
    return (request, scope, chain) -> {
      ClassicHttpResponse response = chain.proceed(request, scope);
      HttpEntity entity = response.getEntity();
      if (entity != null) {
        if (entity.getContentLength() > maxBytes) {
          // Drop the connection rather than drain a body nobody will read.
          scope.execRuntime.discardEndpoint();
          throw new ResponseTooLargeException(maxBytes);
        }
        response.setEntity(new SizeLimitedEntity(entity, maxBytes));
      }
      return response;
    };
  }

  /** A response body exceeded {@link #MAX_RESPONSE_BYTES}. */
  public static final class ResponseTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    ResponseTooLargeException(long maxBytes) {
      super("the response body exceeds the limit of " + maxBytes + " bytes");
    }
  }

  private static final class SizeLimitedEntity extends HttpEntityWrapper {

    private final long maxBytes;

    SizeLimitedEntity(HttpEntity entity, long maxBytes) {
      super(entity);
      this.maxBytes = maxBytes;
    }

    @Override
    public InputStream getContent() throws IOException {
      return new SizeLimitedInputStream(super.getContent(), maxBytes);
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
      try (InputStream in = getContent()) {
        in.transferTo(out);
      }
    }

    @Override
    public boolean isStreaming() {
      return true;
    }
  }

  private static final class SizeLimitedInputStream extends FilterInputStream {

    private final long maxBytes;
    private long read;

    SizeLimitedInputStream(InputStream in, long maxBytes) {
      super(in);
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = super.read(buffer, offset, length);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(int n) throws IOException {
      read += n;
      if (read > maxBytes) {
        throw new ResponseTooLargeException(maxBytes);
      }
    }
  }
}
