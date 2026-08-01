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

import io.qnop.service.http.HttpClientProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches the vendor script and forwards measurements, so the browser never talks to the analytics
 * backend itself (issue #666).
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

  /** The vendor script changes on their release schedule, not ours. */
  private static final Duration SCRIPT_TTL = Duration.ofHours(1);

  /** A measurement is small; anything larger is not one. */
  public static final int MAX_BODY_BYTES = 64 * 1024;

  /** Bounds a hung backend: a measurement is never worth holding a request thread for. */
  private static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(3);

  private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(10);

  private final TrackingConfigService config;
  private final HttpClient http;

  private volatile CachedScript cachedScript;

  /**
   * Whether the last forward failed, so a broken backend is reported once instead of once per
   * measurement.
   *
   * <p>Measurement fails silently by design — a reviewer must never see an analytics error — but
   * silent to the <em>user</em> is not the same as silent to the operator. Without this, "no events
   * are arriving" is a question only a packet capture can answer.
   */
  private final AtomicBoolean forwardingBroken = new AtomicBoolean();

  public TrackingProxyService(TrackingConfigService config, HttpClientProperties httpProperties) {
    this.config = config;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(httpProperties.outboundConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** The vendor script, cached; empty when tracking is off or the backend would not answer. */
  public Optional<Script> script() {
    Optional<TrackingRuntimeConfig> active = config.current();
    if (active.isEmpty()) {
      return Optional.empty();
    }
    String url = active.get().scriptUrl();
    CachedScript cached = cachedScript;
    if (cached != null && cached.matches(url)) {
      return Optional.of(cached.script());
    }
    try {
      HttpResponse<byte[]> response =
          http.send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(SCRIPT_TIMEOUT)
                  .header("Accept", "application/javascript, text/javascript, */*")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        log.warn("The analytics script at {} answered {}", url, response.statusCode());
        return Optional.empty();
      }
      Script script =
          new Script(
              response.body(),
              response
                  .headers()
                  .firstValue("content-type")
                  .orElse("application/javascript; charset=utf-8"));
      cachedScript = new CachedScript(url, script, Instant.now());
      return Optional.of(script);
    } catch (IOException e) {
      log.warn("Could not fetch the analytics script from {}: {}", url, e.getMessage());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
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

    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(target)).timeout(FORWARD_TIMEOUT);
    if ("GET".equalsIgnoreCase(method)) {
      // Matomo and Pirsch measure over the query string. Forwarding those as a
      // POST happens to work for Matomo — it reads parameters from either — but
      // it is not what the browser did, and the next backend need not be so
      // forgiving.
      request.GET();
    } else {
      request.POST(
          HttpRequest.BodyPublishers.ofByteArray(
              sanitizedBody == null ? new byte[0] : sanitizedBody));
    }
    if (contentType != null && !contentType.isBlank()) {
      request.header("Content-Type", contentType);
    }
    if (userAgent != null && !userAgent.isBlank()) {
      request.header("User-Agent", userAgent);
    }
    if (active.forwardClientIp()) {
      ClientIpAnonymizer.anonymize(clientIp)
          .ifPresent(truncated -> request.header("X-Forwarded-For", truncated));
    }

    try {
      HttpResponse<Void> response =
          http.send(request.build(), HttpResponse.BodyHandlers.discarding());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        recovered();
      } else {
        broken("{} answered {} for a measurement", active.collectBaseUrl(), status);
      }
      return Optional.of(status);
    } catch (IOException e) {
      broken("could not reach {}: {}", active.collectBaseUrl(), e.getMessage());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
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

  /** The vendor script as fetched. */
  public record Script(byte[] body, String contentType) {}

  private record CachedScript(String url, Script script, Instant fetchedAt) {
    boolean matches(String candidate) {
      return url.equals(candidate) && fetchedAt.plus(SCRIPT_TTL).isAfter(Instant.now());
    }
  }
}
