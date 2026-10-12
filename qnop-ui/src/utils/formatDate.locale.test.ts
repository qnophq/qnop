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

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ensureLocaleLoaded } from '../i18n';
import { formatDateTime, formatDueDate, formatRelative } from './formatDate';
import { shortRelativeTime } from './relativeTime';

const NOW = new Date('2026-10-12T12:00:00Z');
const DAY = 24 * 60 * 60 * 1000;

describe('formatters in German (issue #464)', () => {
  beforeAll(() => ensureLocaleLoaded('de'));
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });
  afterEach(() => vi.useRealTimers());

  it('formats absolute dates in the German style', () => {
    expect(formatDateTime('2027-01-05T09:30:00Z', 'Europe/Berlin', 'de')).toBe('05.01.2027, 10:30');
  });

  it('keeps the English output unchanged by default', () => {
    expect(formatDateTime('2027-01-05T09:30:00Z', 'Europe/Berlin')).toBe('5 Jan 2027, 10:30');
  });

  it('translates relative and due-date phrases, with plurals', () => {
    expect(formatRelative(new Date(NOW.getTime() - 10_000).toISOString(), 'UTC', 'de')).toBe(
      'gerade eben',
    );
    expect(formatRelative(new Date(NOW.getTime() - 3 * DAY).toISOString(), 'UTC', 'de')).toBe(
      'vor 3 Tagen',
    );
    expect(formatDueDate(new Date(NOW.getTime() + 1 * DAY).toISOString(), 'UTC', 'de')).toBe(
      'fällig in 1 Tag',
    );
    expect(formatDueDate(new Date(NOW.getTime() + 3 * DAY).toISOString(), 'UTC', 'de')).toBe(
      'fällig in 3 Tagen',
    );
    expect(formatDueDate(new Date(NOW.getTime() - 2 * DAY).toISOString(), 'UTC', 'de')).toBe(
      'seit 2 Tagen überfällig',
    );
    expect(formatDueDate(NOW.toISOString(), 'UTC', 'de')).toBe('heute fällig');
  });

  it('renders the hook-level date styles in en-GB for English, de-DE for German', () => {
    // English deliberately uses the en-GB style everywhere (day before month), matching
    // formatDateTime; the former en-US masthead and browser-locale deadline dates were the
    // odd ones out (issue #464).
    const longDay = (locale: string) =>
      new Intl.DateTimeFormat(locale, {
        weekday: 'long',
        day: 'numeric',
        month: 'long',
        timeZone: 'UTC',
      }).format(NOW);
    expect(longDay('en-GB')).toBe('Monday 12 October');
    expect(longDay('de-DE')).toBe('Montag, 12. Oktober');
  });

  it('renders the compact feed timestamps in German', () => {
    const iso = new Date(NOW.getTime() - 5 * 60_000).toISOString();
    expect(shortRelativeTime(iso, NOW, 'UTC', 'de')).toBe('5 Min.');
    expect(shortRelativeTime(iso, NOW, 'UTC')).toBe('5m');
  });
});
