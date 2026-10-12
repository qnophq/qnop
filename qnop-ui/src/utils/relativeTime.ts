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

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

// Built once per (locale, zone) and reused (see formatDate.ts for the rationale).
const monthDayCache = new Map<string, Intl.DateTimeFormat>();
const monthDayYearCache = new Map<string, Intl.DateTimeFormat>();

function monthDayFmt(timeZone: string, locale: AppLocale, withYear: boolean): Intl.DateTimeFormat {
  const cache = withYear ? monthDayYearCache : monthDayCache;
  const key = `${locale}|${timeZone}`;
  let fmt = cache.get(key);
  if (!fmt) {
    // English keeps its month-first "Jul 5" here (the feed style), so it formats with plain `en`.
    fmt = new Intl.DateTimeFormat(locale === 'en' ? 'en' : intlLocale(locale), {
      month: 'short',
      day: 'numeric',
      ...(withYear ? { year: 'numeric' } : {}),
      timeZone,
    });
    cache.set(key, fmt);
  }
  return fmt;
}

/**
 * The compact relative timestamp of social feeds (issue #403): "now", "5m",
 * "3h", "6d", then the calendar date ("Jul 5", with the year once it
 * differs). Callers put the full timestamp in a tooltip for precision. The
 * calendar-date fallback renders in {@code timeZone} (ADR-0041; default UTC)
 * and every part in {@code locale} (issue #464; default English).
 */
export function shortRelativeTime(
  iso: string,
  now: Date = new Date(),
  timeZone: string = FALLBACK_TIME_ZONE,
  locale: AppLocale = FALLBACK_LOCALE,
): string {
  const then = new Date(iso);
  const diff = now.getTime() - then.getTime();
  const lng = locale;
  if (diff < MINUTE) return i18n.t('time.compact.now', { lng });
  if (diff < HOUR) return i18n.t('time.compact.minutes', { lng, count: Math.floor(diff / MINUTE) });
  if (diff < DAY) return i18n.t('time.compact.hours', { lng, count: Math.floor(diff / HOUR) });
  if (diff < 7 * DAY) return i18n.t('time.compact.days', { lng, count: Math.floor(diff / DAY) });
  const withYear = then.getFullYear() !== now.getFullYear();
  return monthDayFmt(timeZone, locale, withYear).format(then);
}
