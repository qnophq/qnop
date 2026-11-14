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
package io.qnop.service.tracking;

import io.qnop.observability.metrics.TrackingMeters;
import io.qnop.observability.metrics.TrackingMeters.Outcome;
import io.qnop.service.http.HttpClientProperties;
import io.qnop.service.http.OutboundHttpClients;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Forwards measurements, so the browser never talks to the analytics backend itself (issue #666).
 *
 * <p>It forwards and nothing else: no vendor script is fetched or served (issue #910) — qnop's own
 * client builds each measurement in the backend's collect format.
 *
 * <p>The SPA's Content-Security-Policy pins scripts and connections to {@code 'self'} (ADR-0040).
 * Rather than weakening that per deployment, this server stands in the middle — which turns a
 * constraint into three properties worth having: the policy stays as strict as it is on every other
 * page, a reviewer's address never reaches a third party except truncated and on purpose, and an ad
 * blocker has no third-party host to block.
 *
 * <p>Only the paths a provider declares are forwarded ({@link TrackingProvider#allowsCollectPath}),
 * so this is a conduit to one configured backend, never an open relay.
 */
@Service
public class TrackingProxyService {

  private static final Logger log = LoggerFactory.getLogger(TrackingProxyService.class);

  /** A measurement is small; anything larger is not one. */
  public static final int MAX_BODY_BYTES = 64 * 1024;

  /** Bounds a hung backend: a measurement is never worth holding a request thread for. */
  private static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(3);

  private final TrackingConfigService config;
  private final CloseableHttpClient http;
  private final RequestConfig requestDefaults;
  private final TrackingMeters meters;

  /**
   * Whether the last forward failed, so a broken backend is reported once instead of once per
   * measurement.
   *
   * <p>Measurement fails silently by design — a reviewer must never see an analytics error — but
   * silent to the <em>user</em> is not the same as silent to the operator. Without this, "no events
   * are arriving" is a question only a packet capture can answer.
   */
  private final AtomicBoolean forwardingBroken = new AtomicBoolean();

  /**
   * The outbound client is guarded (issue #904): it resolves the tracking host itself, refuses a
   * blocked address unless {@code qnop.tracking.allow-private-host} is set, connects to exactly the
   * address it validated, never follows a redirect, reads at most {@link
   * OutboundHttpClients#MAX_RESPONSE_BYTES} of a response, and waits for a pooled connection no
   * longer than the request may take as a whole.
   */
  public TrackingProxyService(
      TrackingConfigService config,
      HttpClientProperties httpProperties,
      @Value("${qnop.tracking.allow-private-host:false}") boolean allowPrivateHost,
      TrackingMeters meters) {
    this.config = config;
    this.meters = meters;
    this.http = OutboundHttpClients.create(httpProperties, allowPrivateHost);
    this.requestDefaults = OutboundHttpClients.defaultRequestConfig(httpProperties);
  }

  /** Releases the pooled connections with the application context. */
  @PreDestroy
  void close() throws IOException {
    http.close();
  }

  /**
   * Forwards one measurement.
   *
   * @param method the browser's HTTP method — Matomo and Pirsch measure over GET, the rest POST
   * @param path the collect path the browser asked for; refused unless the provider declares it
   * @param query the query string as sent, sanitized here before it travels
   * @param body the request body, sanitized here before it travels
   * @param contentType the browser's content type, passed through
   * @param clientIp the caller's address, truncated before it travels (or dropped entirely)
   * @param userAgent the caller's user agent — needed by every backend to tell visitors apart
   * @return the backend's status code, or empty when the request was refused or never got through
   */
  public Optional<Integer> forward(
      String method,
      String path,
      String query,
      byte[] body,
      String contentType,
      String clientIp,
      String userAgent) {
    Optional<TrackingRuntimeConfig> maybe = config.current();
    if (maybe.isEmpty()) {
      return Optional.empty();
    }
    TrackingRuntimeConfig active = maybe.get();
    if (!active.provider().allowsCollectPath(path)) {
      // Not an error worth a stack trace: either a stale bundle or somebody
      // probing what else this endpoint will reach.
      log.debug("Refused a measurement for an undeclared path {}", path);
      return Optional.empty();
    }

    String sanitizedQuery = TrackingPayloadSanitizer.sanitizeQuery(query);
    byte[] sanitizedBody = TrackingPayloadSanitizer.sanitizeBody(body);
    String target =
        active.collectBaseUrl()
            + path
            + (sanitizedQuery == null || sanitizedQuery.isBlank() ? "" : "?" + sanitizedQuery);

    HttpUriRequestBase request;
    if ("GET".equalsIgnoreCase(method)) {
      // Matomo and Pirsch measure over the query string. Forwarding those as a
      // POST happens to work for Matomo — it reads parameters from either — but
      // it is not what the browser did, and the next backend need not be so
      // forgiving.
      request = new HttpGet(URI.create(target));
    } else {
      HttpPost post = new HttpPost(URI.create(target));
      post.setEntity(
          new ByteArrayEntity(sanitizedBody == null ? new byte[0] : sanitizedBody, null));
      request = post;
    }
    request.setConfig(timeout(FORWARD_TIMEOUT));
    if (contentType != null && !contentType.isBlank()) {
      request.setHeader("Content-Type", contentType);
    }
    if (userAgent != null && !userAgent.isBlank()) {
      request.setHeader("User-Agent", userAgent);
    }
    // Truncated, exact or nothing (issues #666/#712) — and in every case parsed
    // first, so an address this server could not read travels in no mode at all.
    active
        .forwardClientIp()
        .format(clientIp)
        .ifPresent(forwarded -> request.setHeader("X-Forwarded-For", forwarded));

    long started = System.nanoTime();
    try {
      int status =
          http.execute(
              request,
              response -> {
                EntityUtils.consume(response.getEntity());
                return response.getCode();
              });
      boolean accepted = status >= 200 && status < 300;
      measured(active, accepted ? Outcome.SUCCESS : Outcome.REJECTED, started);
      if (accepted) {
        recovered();
      } else {
        broken("{} answered {} for a measurement", active.collectBaseUrl(), status);
      }
      return Optional.of(status);
    } catch (IOException e) {
      measured(active, Outcome.ERROR, started);
      broken("could not reach {}: {}", active.collectBaseUrl(), e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * The client defaults with this call's bound. A per-request config replaces the client's, so it
   * starts from a copy; the pool-lease wait is capped by the same bound, so a slow backend that has
   * filled the pool cannot hold a request thread longer than one measurement may take.
   */
  private RequestConfig timeout(Duration timeout) {
    Timeout bound = Timeout.of(timeout);
    return RequestConfig.copy(requestDefaults)
        .setResponseTimeout(bound)
        .setConnectionRequestTimeout(bound)
        .build();
  }

  /** Every forward is measured (issue #962); the log above speaks only on a change of state. */
  private void measured(TrackingRuntimeConfig active, Outcome outcome, long startedNanos) {
    meters.forwarded(
        active.provider(), outcome, Duration.ofNanos(System.nanoTime() - startedNanos));
  }

  /** Reports a broken backend on the transition into that state, never once per measurement. */
  private void broken(String message, Object... arguments) {
    if (forwardingBroken.compareAndSet(false, true)) {
      log.warn("Usage tracking is not being recorded — " + message, arguments);
    }
  }

  private void recovered() {
    if (forwardingBroken.compareAndSet(true, false)) {
      log.info("Usage tracking is being recorded again");
    }
  }
}
