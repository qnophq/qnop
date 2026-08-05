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

import java.util.Locale;
import java.util.Optional;

/**
 * How much of the visitor's address travels to the analytics backend (issues #666/#712).
 *
 * <p>Three settings rather than two, because a self-hosted operator turned out to need a case the
 * pair could not express: seeing exact addresses in their <em>own</em> analytics instance, to tell
 * a returning evaluator from a bot. That is not a disclosure to a third party — but it is personal
 * data, so it is opt-in and never the default.
 */
public enum ClientIpForwarding {
  /** Nothing travels; the backend counts every visitor as one. */
  NONE,
  /** Truncated to /24 or /64 — countable without being identifiable. The default. */
  ANONYMIZED,
  /** The exact address. Personal data, and the operator's legal basis to hold. */
  FULL;

  /** Reads the stored setting value, defaulting to {@link #ANONYMIZED} for anything unexpected. */
  public static ClientIpForwarding parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return ANONYMIZED;
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "none" -> NONE;
      case "full" -> FULL;
      // Including "anonymized" and anything unrecognised: an unreadable value must
      // fall to the privacy-preserving option, never to the loosest one.
      default -> ANONYMIZED;
    };
  }

  /**
   * What to put in {@code X-Forwarded-For}, or empty when nothing should travel.
   *
   * <p>Both modes that send anything parse first, so an address this server could not understand is
   * forwarded in no mode at all.
   */
  public Optional<String> format(String clientIp) {
    return switch (this) {
      case NONE -> Optional.empty();
      case ANONYMIZED -> ClientIpFormatter.anonymize(clientIp);
      case FULL -> ClientIpFormatter.normalize(clientIp);
    };
  }
}
