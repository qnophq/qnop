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
 * <p>Each entry is three facts: which script the browser loads, which paths the browser is allowed
 * to send to, and — for the hosted ones — where that goes by default. Everything else about the
 * backend is the backend's business.
 *
 * <p><strong>The collect list is a boundary, not a convenience.</strong> The proxy forwards a path
 * only if it appears here, which is what makes a promise like "no session recording" enforceable:
 * PostHog's {@code /s/} is absent, so a client that somehow asked for session replay would be
 * refused by this server rather than trusted not to ask. The same list keeps the endpoint from
 * being a general-purpose relay to the analytics host.
 *
 * <p>The script variants are chosen for one reason: none of them may report page views on their
 * own. qnop sends its own, built from route patterns, because the real URL of a review contains its
 * id (see {@link TrackedUrlSanitizer}).
 */
public enum TrackingProvider {

  /** Self-hosted only. {@code matomo.js} tracks nothing until {@code trackPageView} is called. */
  MATOMO("matomo", "/matomo.js", List.of("/matomo.php"), null, null),

  /**
   * {@code script.manual.js} is the variant that does <em>not</em> track automatically — the
   * default {@code script.js} would report the real URL on load, id and all.
   */
  PLAUSIBLE(
      "plausible", "/js/script.manual.js", List.of("/api/event"), "https://plausible.io", null),

  /** Self-hosted only; auto-tracking is switched off through a data attribute on the tag. */
  UMAMI("umami", "/script.js", List.of("/api/send", "/api/batch"), null, null),

  /**
   * Cloud PostHog splits assets and ingestion across two hosts; a self-hosted one serves both.
   *
   * <p>{@code /s/} (session recording) and {@code /static/recorder.js} are deliberately absent:
   * qnop renders customers' documents, and a recording of that screen is a copy of the document.
   */
  POSTHOG(
      "posthog",
      "/static/array.js",
      List.of("/e", "/e/", "/i/v0/e/", "/batch", "/batch/", "/decide/", "/flags", "/flags/"),
      "https://us.i.posthog.com",
      "https://us-assets.i.posthog.com"),

  /** Built for proxying: the script takes its endpoints as data attributes. */
  PIRSCH("pirsch", "/pa.js", List.of("/pv", "/e", "/s"), "https://api.pirsch.io", null);

  private final String id;
  private final String scriptPath;
  private final List<String> collectPaths;
  private final String defaultHost;
  private final String defaultScriptHost;

  TrackingProvider(
      String id,
      String scriptPath,
      List<String> collectPaths,
      String defaultHost,
      String defaultScriptHost) {
    this.id = id;
    this.scriptPath = scriptPath;
    this.collectPaths = List.copyOf(collectPaths);
    this.defaultHost = defaultHost;
    this.defaultScriptHost = defaultScriptHost;
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

  public String scriptPath() {
    return scriptPath;
  }

  public List<String> collectPaths() {
    return collectPaths;
  }

  /** Where measurements go when the operator configured no host; null where there is no cloud. */
  public String defaultHost() {
    return defaultHost;
  }

  /**
   * Where the script comes from when no host is configured; null means "same as {@link
   * #defaultHost()}".
   */
  public String defaultScriptHost() {
    return defaultScriptHost != null ? defaultScriptHost : defaultHost;
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
