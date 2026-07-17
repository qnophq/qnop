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

import { FALLBACK_TIME_ZONE } from './timezone';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

// Built once per resolved zone and reused (see formatDate.ts for the rationale).
const monthDayCache = new Map<string, Intl.DateTimeFormat>();
const monthDayYearCache = new Map<string, Intl.DateTimeFormat>();

function monthDayFmt(timeZone: string): Intl.DateTimeFormat {
  let fmt = monthDayCache.get(timeZone);
  if (!fmt) {
    fmt = new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric', timeZone });
    monthDayCache.set(timeZone, fmt);
  }
  return fmt;
}

function monthDayYearFmt(timeZone: string): Intl.DateTimeFormat {
  let fmt = monthDayYearCache.get(timeZone);
  if (!fmt) {
    fmt = new Intl.DateTimeFormat('en', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      timeZone,
    });
    monthDayYearCache.set(timeZone, fmt);
  }
  return fmt;
}

/**
 * The compact relative timestamp of social feeds (issue #403): "now", "5m",
 * "3h", "6d", then the calendar date ("Jul 5", with the year once it
 * differs). Callers put the full timestamp in a tooltip for precision. The
 * calendar-date fallback renders in {@code timeZone} (ADR-0039; default UTC).
 */
export function shortRelativeTime(
  iso: string,
  now: Date = new Date(),
  timeZone: string = FALLBACK_TIME_ZONE,
): string {
  const then = new Date(iso);
  const diff = now.getTime() - then.getTime();
  if (diff < MINUTE) return 'now';
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)}m`;
  if (diff < DAY) return `${Math.floor(diff / HOUR)}h`;
  if (diff < 7 * DAY) return `${Math.floor(diff / DAY)}d`;
  return then.getFullYear() === now.getFullYear()
    ? monthDayFmt(timeZone).format(then)
    : monthDayYearFmt(timeZone).format(then);
}
