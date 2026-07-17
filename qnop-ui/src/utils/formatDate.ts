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

// Each Intl.DateTimeFormat is built once per resolved zone and reused, so switching a display zone
// costs a Map lookup, not a fresh formatter on every render. The zone defaults to UTC (ADR-0041):
// callers render through useFormatters(), which passes the user's resolved zone.
const dateTimeCache = new Map<string, Intl.DateTimeFormat>();
const dateOnlyCache = new Map<string, Intl.DateTimeFormat>();

function dateTimeFmt(timeZone: string): Intl.DateTimeFormat {
  let fmt = dateTimeCache.get(timeZone);
  if (!fmt) {
    fmt = new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short', timeZone });
    dateTimeCache.set(timeZone, fmt);
  }
  return fmt;
}

function dateOnlyFmt(timeZone: string): Intl.DateTimeFormat {
  let fmt = dateOnlyCache.get(timeZone);
  if (!fmt) {
    fmt = new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeZone });
    dateOnlyCache.set(timeZone, fmt);
  }
  return fmt;
}

const RELATIVE = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/** Formats an ISO timestamp as a localized date+time, or an em dash when absent/invalid. */
export function formatDateTime(
  iso: string | null | undefined,
  timeZone: string = FALLBACK_TIME_ZONE,
): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : dateTimeFmt(timeZone).format(date);
}

/**
 * Formats an ISO timestamp relative to now ("just now", "3 days ago"), falling
 * back to an absolute date beyond 30 days and to an em dash when absent/invalid.
 */
export function formatRelative(
  iso: string | null | undefined,
  timeZone: string = FALLBACK_TIME_ZONE,
): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const diffMs = date.getTime() - Date.now();
  const absMs = Math.abs(diffMs);
  if (absMs < MINUTE_MS) return 'just now';
  if (absMs < HOUR_MS) return RELATIVE.format(Math.round(diffMs / MINUTE_MS), 'minute');
  if (absMs < DAY_MS) return RELATIVE.format(Math.round(diffMs / HOUR_MS), 'hour');
  if (absMs < 30 * DAY_MS) return RELATIVE.format(Math.round(diffMs / DAY_MS), 'day');
  return dateTimeFmt(timeZone).format(date);
}

/** Whether an ISO timestamp is in the past relative to {@code now} (default: now). */
export function isPast(iso: string | null | undefined, now: number = Date.now()): boolean {
  if (!iso) return false;
  const time = new Date(iso).getTime();
  return !Number.isNaN(time) && time < now;
}

/**
 * A due date phrased for a deadline: "due today", "due in 3 days", "overdue by
 * 2 days", falling back to an absolute date beyond 30 days ("due 5 Jan 2027" /
 * "was due 5 Jan 2020") and to an em dash when absent/invalid.
 */
export function formatDueDate(
  iso: string | null | undefined,
  timeZone: string = FALLBACK_TIME_ZONE,
): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const diffMs = date.getTime() - Date.now();
  if (Math.abs(diffMs) >= 30 * DAY_MS) {
    return `${diffMs < 0 ? 'was due' : 'due'} ${dateOnlyFmt(timeZone).format(date)}`;
  }
  const days = Math.round(diffMs / DAY_MS);
  if (days === 0) return 'due today';
  if (days > 0) return `due in ${days} day${days === 1 ? '' : 's'}`;
  const overdueDays = -days;
  return `overdue by ${overdueDays} day${overdueDays === 1 ? '' : 's'}`;
}
