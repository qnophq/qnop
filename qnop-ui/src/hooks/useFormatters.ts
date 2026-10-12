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

import { useMemo } from 'react';
import { intlLocale, type AppLocale } from '../i18n/locales';
import { useUiStore } from '../stores/uiStore';
import { formatDateTime, formatDueDate, formatRelative, isPast } from '../utils/formatDate';
import { shortRelativeTime } from '../utils/relativeTime';

type DateInput = string | Date | null | undefined;

function toDate(value: DateInput): Date | undefined {
  if (!value) return undefined;
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

function formatWith(options: Intl.DateTimeFormatOptions, timeZone: string, locale: AppLocale) {
  const fmt = new Intl.DateTimeFormat(intlLocale(locale), { ...options, timeZone });
  return (value: DateInput) => {
    const date = toDate(value);
    return date ? fmt.format(date) : '—';
  };
}

/**
 * Date/time formatters bound to the viewer's active display zone (issue #465,
 * ADR-0041) and UI language (issue #464, ADR-0063). This is the single seam
 * through which components render timestamps: every returned formatter carries
 * the resolved timezone (user profile → application default → UTC) and locale,
 * so no component renders in the raw browser zone or a hardcoded language.
 *
 * The returned object is memoized on zone and locale, so a component re-render
 * does not rebuild the closures and the wrapped formatters keep a stable
 * identity. Both are read from the UI store (populated by TimezoneSync and
 * LanguageSync), so a component can format without depending on a QueryClient.
 */
export function useFormatters() {
  const timeZone = useUiStore((s) => s.displayTimeZone);
  const locale = useUiStore((s) => s.locale);
  return useMemo(
    () => ({
      /** The active IANA display zone, e.g. for showing "(Europe/Berlin)" next to a raw time. */
      timeZone,
      /** The active UI language. */
      locale,
      formatDateTime: (iso: string | null | undefined) => formatDateTime(iso, timeZone, locale),
      formatRelative: (iso: string | null | undefined) => formatRelative(iso, timeZone, locale),
      formatDueDate: (iso: string | null | undefined) => formatDueDate(iso, timeZone, locale),
      /** A calendar date without time, e.g. "5 Jan 2027" / "05.01.2027". */
      formatDate: formatWith({ dateStyle: 'medium' }, timeZone, locale),
      /** Month and year, e.g. "October 2026" — the "member since" style. */
      formatMonthYear: formatWith({ month: 'long', year: 'numeric' }, timeZone, locale),
      /** Weekday, day and month, e.g. "Monday 12 October" — the dashboard masthead style. */
      formatLongDay: formatWith(
        { weekday: 'long', day: 'numeric', month: 'long' },
        timeZone,
        locale,
      ),
      /** Zone-independent (compares epoch millis); re-exported so callers use one seam. */
      isPast,
      shortRelativeTime: (iso: string, now?: Date) => shortRelativeTime(iso, now, timeZone, locale),
    }),
    [timeZone, locale],
  );
}
