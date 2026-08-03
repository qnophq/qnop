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
package io.qnop.service.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Whether a recipient's digest is due right now (issue #680).
 *
 * <p>The rule is "the first run at or after {@value #SEND_HOUR}:00 local time, at most once per
 * local day" rather than "the run at 08:00". That distinction is what makes the schedule's
 * frequency an implementation detail instead of the semantics: an hourly cron would miss the
 * half-hour offsets — 08:00 in Kolkata is 02:30 UTC — while "at or after" simply delivers at 08:30
 * there. Running more often only makes it more punctual, never wrong.
 *
 * <p>Pure and clock-injected, because every interesting case here is a date and a timezone.
 */
public final class DigestSchedule {

  /** Local hour from which a digest may go out. Morning, not midnight: it is meant to be read. */
  public static final int SEND_HOUR = 8;

  /**
   * How far back a first-ever digest reaches.
   *
   * <p>Somebody who switches to DAILY may have weeks of unread notifications; mailing all of them
   * as a "daily" summary would be a worse first impression than the per-event mail they left.
   */
  public static final java.time.Duration FIRST_RUN_LOOKBACK = java.time.Duration.ofHours(24);

  private DigestSchedule() {}

  /**
   * @param zone the recipient's timezone
   * @param lastSentAt when their last digest went out, or empty if they never had one
   */
  public static boolean isDue(Instant now, ZoneId zone, Optional<Instant> lastSentAt) {
    ZonedDateTime local = now.atZone(zone);
    if (local.getHour() < SEND_HOUR) {
      return false;
    }
    LocalDate today = local.toLocalDate();
    // Both sides converted with the same zone, so the comparison is "was the last
    // one on an earlier day for this recipient" rather than a server-date guess.
    return lastSentAt.map(sent -> sent.atZone(zone).toLocalDate().isBefore(today)).orElse(true);
  }

  /** The instant a digest should collect from: the watermark, or a bounded first-run window. */
  public static Instant collectFrom(Instant now, Optional<Instant> coveredThrough) {
    return coveredThrough.orElseGet(() -> now.minus(FIRST_RUN_LOOKBACK));
  }

  /** The recipient's local date, for logs and tests that reason in calendar days. */
  public static LocalDate localDate(Instant now, ZoneId zone) {
    return now.atZone(zone).toLocalDate();
  }
}
