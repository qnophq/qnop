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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The analytics backends qnop can forward measurements to (issue #666).
 *
 * <p>Each entry is two facts: which paths the browser is allowed to send to, and — for the hosted
 * ones — where that goes by default. Everything else about the backend is the backend's business.
 *
 * <p><strong>No vendor script is involved (issue #910).</strong> qnop's own client builds every
 * measurement in the backend's documented collect format and sends it here; the backend's
 * JavaScript is never fetched, served or executed. A script served from this origin would run with
 * the application's full privileges, so a compromised or misconfigured analytics host would have
 * been code execution in every consenting reviewer's session.
 *
 * <p><strong>The collect list is a boundary, not a convenience.</strong> The proxy forwards a path
 * only if it appears here, and the list holds exactly the paths qnop's client sends — one for page
 * views, one for events (they coincide for most backends). PostHog's {@code /s/} (session
 * recording), its {@code /decide/} and {@code /flags/} configuration endpoints and every batch
 * endpoint are absent, so they are refused by this server rather than trusted not to be asked for.
 * The same list keeps the endpoint from being a general-purpose relay to the analytics host.
 */
public enum TrackingProvider {

  /** Self-hosted only; the HTTP Tracking API. */
  MATOMO("matomo", List.of("/matomo.php"), null),

  /** The Events API, cloud by default. */
  PLAUSIBLE("plausible", List.of("/api/event"), "https://plausible.io"),

  /** Self-hosted only; the {@code /api/send} collect endpoint. */
  UMAMI("umami", List.of("/api/send"), null),

  /**
   * The capture endpoint, cloud ingestion by default; a self-hosted PostHog serves it too.
   *
   * <p>{@code /s/} (session recording) is deliberately absent: qnop renders customers' documents,
   * and a recording of that screen is a copy of the document.
   */
  POSTHOG("posthog", List.of("/i/v0/e/"), "https://us.i.posthog.com"),

  /** Page views as a GET to {@code /hit}, events as a POST to {@code /event}. */
  PIRSCH("pirsch", List.of("/hit", "/event"), "https://api.pirsch.io");

  private final String id;
  private final List<String> collectPaths;
  private final String defaultHost;

  TrackingProvider(String id, List<String> collectPaths, String defaultHost) {
    this.id = id;
    this.collectPaths = List.copyOf(collectPaths);
    this.defaultHost = defaultHost;
  }

  public static Optional<TrackingProvider> fromId(String id) {
    if (id == null) {
      return Optional.empty();
    }
    String normalized = id.trim().toLowerCase(Locale.ROOT);
    for (TrackingProvider provider : values()) {
      if (provider.id.equals(normalized)) {
        return Optional.of(provider);
      }
    }
    return Optional.empty();
  }

  public String id() {
    return id;
  }

  public List<String> collectPaths() {
    return collectPaths;
  }

  /** Where measurements go when the operator configured no host; null where there is no cloud. */
  public String defaultHost() {
    return defaultHost;
  }

  /** True where the backend exists only self-hosted, so a host must be configured. */
  public boolean requiresHost() {
    return defaultHost == null;
  }

  /**
   * Whether the browser may send to this path.
   *
   * <p>Compared exactly rather than by prefix: a prefix match on {@code /e} would also admit {@code
   * /export-everything}, and the point of the list is that nothing beyond it gets through.
   */
  public boolean allowsCollectPath(String path) {
    return path != null && collectPaths.contains(path);
  }
}
