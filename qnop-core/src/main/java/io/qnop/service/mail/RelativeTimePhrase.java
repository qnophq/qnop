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
package io.qnop.service.mail;

import java.time.Duration;
import java.time.Instant;

/**
 * Renders a coarse, human-friendly "expires in …" phrase for mail bodies (issue #140), so the
 * {@code expiresAtHuman} template variable reads naturally ("in 30 minutes", "in 24 hours", "in 7
 * days") regardless of the exact token TTL. Rounded to the nearest sensible unit; never negative.
 */
public final class RelativeTimePhrase {

  private RelativeTimePhrase() {}

  /** A phrase like {@code "in 30 minutes"} for the time from now until {@code expiresAt}. */
  public static String until(Instant expiresAt) {
    return until(Instant.now(), expiresAt);
  }

  static String until(Instant now, Instant expiresAt) {
    long minutes = Math.max(1, Math.round(Duration.between(now, expiresAt).toSeconds() / 60.0));
    if (minutes < 60) {
      return "in " + plural(minutes, "minute");
    }
    long hours = Math.round(minutes / 60.0);
    if (hours < 48) {
      return "in " + plural(hours, "hour");
    }
    long days = Math.round(hours / 24.0);
    return "in " + plural(days, "day");
  }

  private static String plural(long count, String unit) {
    return count + " " + unit + (count == 1 ? "" : "s");
  }
}
