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

import i18n from '../i18n';
import { FALLBACK_LOCALE, intlLocale, type AppLocale } from '../i18n/locales';
import { FALLBACK_TIME_ZONE } from './timezone';

// Each Intl formatter is built once per (locale, zone) and reused, so switching a display zone or
// language costs a Map lookup, not a fresh formatter on every render. The zone defaults to UTC
// (ADR-0041) and the language to English (issue #464): callers render through useFormatters(),
// which passes the viewer's resolved zone and language.
const dateTimeCache = new Map<string, Intl.DateTimeFormat>();
const dateOnlyCache = new Map<string, Intl.DateTimeFormat>();
const relativeCache = new Map<AppLocale, Intl.RelativeTimeFormat>();

function cached<T>(cache: Map<string, T>, key: string, create: () => T): T {
  let value = cache.get(key);
  if (!value) {
    value = create();
    cache.set(key, value);
  }
  return value;
}

function dateTimeFmt(timeZone: string, locale: AppLocale): Intl.DateTimeFormat {
  return cached(
    dateTimeCache,
    `${locale}|${timeZone}`,
    () =>
      new Intl.DateTimeFormat(intlLocale(locale), {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone,
      }),
  );
}

function dateOnlyFmt(timeZone: string, locale: AppLocale): Intl.DateTimeFormat {
  return cached(
    dateOnlyCache,
    `${locale}|${timeZone}`,
    () => new Intl.DateTimeFormat(intlLocale(locale), { dateStyle: 'medium', timeZone }),
  );
}

function relativeFmt(locale: AppLocale): Intl.RelativeTimeFormat {
  let fmt = relativeCache.get(locale);
  if (!fmt) {
    fmt = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
    relativeCache.set(locale, fmt);
  }
  return fmt;
}

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/** Formats an ISO timestamp as a localized date+time, or an em dash when absent/invalid. */
export function formatDateTime(
  iso: string | null | undefined,
  timeZone: string = FALLBACK_TIME_ZONE,
  locale: AppLocale = FALLBACK_LOCALE,
): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : dateTimeFmt(timeZone, locale).format(date);
}

/**
 * Formats an ISO timestamp relative to now ("just now", "3 days ago"), falling
 * back to an absolute date beyond 30 days and to an em dash when absent/invalid.
 */
export function formatRelative(
  iso: string | null | undefined,
  timeZone: string = FALLBACK_TIME_ZONE,
  locale: AppLocale = FALLBACK_LOCALE,
): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const diffMs = date.getTime() - Date.now();
  const absMs = Math.abs(diffMs);
  const relative = relativeFmt(locale);
  if (absMs < MINUTE_MS) return i18n.t('time.justNow', { lng: locale });
  if (absMs < HOUR_MS) return relative.format(Math.round(diffMs / MINUTE_MS), 'minute');
  if (absMs < DAY_MS) return relative.format(Math.round(diffMs / HOUR_MS), 'hour');
  if (absMs < 30 * DAY_MS) return relative.format(Math.round(diffMs / DAY_MS), 'day');
  return dateTimeFmt(timeZone, locale).format(date);
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
  locale: AppLocale = FALLBACK_LOCALE,
): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const diffMs = date.getTime() - Date.now();
  const lng = locale;
  if (Math.abs(diffMs) >= 30 * DAY_MS) {
    const formatted = dateOnlyFmt(timeZone, locale).format(date);
    return diffMs < 0
      ? i18n.t('time.due.wasDueOn', { lng, date: formatted })
      : i18n.t('time.due.on', { lng, date: formatted });
  }
  const days = Math.round(diffMs / DAY_MS);
  if (days === 0) return i18n.t('time.due.today', { lng });
  if (days > 0) return i18n.t('time.due.inDays', { lng, count: days });
  return i18n.t('time.due.overdueBy', { lng, count: -days });
}
