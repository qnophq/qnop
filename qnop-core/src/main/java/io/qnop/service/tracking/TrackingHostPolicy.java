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

import io.qnop.service.http.OutboundUriGuard;

/**
 * The transport rule for the analytics host: https, or plain http only for a lexically private host
 * (issue #910).
 *
 * <p>Every forwarded measurement carries the visitor's user agent and — truncated or, by opt-in,
 * exact — address. Across the public internet that travels encrypted or not at all. A self-hosted
 * backend on {@code 10.0.0.5} or {@code matomo.internal} may stay on http: that traffic never
 * leaves the operator's network, and reaching it at all needs the deployment's private-host opt-in.
 *
 * <p>"Private" is decided by the same lexical policy the SSRF guard uses, with no DNS lookup — so
 * the rule gives the same answer when a setting is saved and when it is used, and a public name
 * that happens to resolve to a private address must still use https.
 */
public final class TrackingHostPolicy {

  private TrackingHostPolicy() {}

  /**
   * Whether {@code url} — already known to be a syntactically valid http(s) URL — may be used as
   * the analytics host as far as its transport is concerned.
   */
  public static boolean hasAcceptableTransport(String url) {
    String trimmed = url.strip();
    if (trimmed.regionMatches(true, 0, "https://", 0, "https://".length())) {
      return true;
    }
    return isLexicallyPrivate(trimmed);
  }

  private static boolean isLexicallyPrivate(String url) {
    try {
      // Accepted with private hosts allowed: a well-formed URL whose host is not
      // refused outright (e.g. a non-canonical numeric spelling).
      OutboundUriGuard.requireAllowedHttpUri(url, "tracking.host", true, true);
    } catch (IllegalArgumentException malformed) {
      return false;
    }
    try {
      OutboundUriGuard.requireAllowedHttpUri(url, "tracking.host", true, false);
      return false;
    } catch (IllegalArgumentException blocked) {
      // Accepted above but refused under the strict policy — which means a private,
      // loopback or internal host.
      return true;
    }
  }
}
