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

import { describe, expect, it } from 'vitest';
import { formatDateTime, formatDueDate, formatRelative, isPast } from './formatDate';

const DAY_MS = 24 * 60 * 60_000;

describe('formatDateTime', () => {
  it('returns an em dash for null, undefined or an invalid date', () => {
    expect(formatDateTime(null)).toBe('—');
    expect(formatDateTime(undefined)).toBe('—');
    expect(formatDateTime('not-a-date')).toBe('—');
  });

  it('formats a valid ISO timestamp to a non-empty localized string', () => {
    const formatted = formatDateTime('2026-06-21T12:34:00Z');
    expect(formatted).not.toBe('—');
    expect(formatted).toMatch(/2026/);
  });

  it('renders the same instant differently under different display zones (issue #465)', () => {
    // 23:30 UTC is already the next calendar day in Tokyo (UTC+9 → 08:30).
    const iso = '2026-07-12T23:30:00Z';
    expect(formatDateTime(iso, 'UTC')).toMatch(/12 Jul 2026/);
    expect(formatDateTime(iso, 'Asia/Tokyo')).toMatch(/13 Jul 2026/);
    expect(formatDateTime(iso, 'UTC')).not.toBe(formatDateTime(iso, 'Asia/Tokyo'));
  });

  it('defaults to UTC when no zone is passed', () => {
    const iso = '2026-07-12T23:30:00Z';
    expect(formatDateTime(iso)).toBe(formatDateTime(iso, 'UTC'));
  });
});

describe('formatRelative', () => {
  it('returns an em dash for null, undefined or an invalid date', () => {
    expect(formatRelative(null)).toBe('—');
    expect(formatRelative(undefined)).toBe('—');
    expect(formatRelative('not-a-date')).toBe('—');
  });

  it('reports a very recent timestamp as "just now"', () => {
    expect(formatRelative(new Date(Date.now() - 5_000).toISOString())).toBe('just now');
  });

  it('reports a few hours ago relatively', () => {
    const threeHoursAgo = new Date(Date.now() - 3 * 60 * 60_000).toISOString();
    expect(formatRelative(threeHoursAgo)).toMatch(/hour/);
  });

  it('falls back to an absolute date beyond 30 days', () => {
    const longAgo = new Date(Date.now() - 200 * 24 * 60 * 60_000).toISOString();
    expect(formatRelative(longAgo)).not.toBe('—');
    expect(formatRelative(longAgo)).not.toMatch(/ago|just now/);
  });
});

describe('isPast', () => {
  it('is false for null, undefined or an invalid date', () => {
    expect(isPast(null)).toBe(false);
    expect(isPast(undefined)).toBe(false);
    expect(isPast('not-a-date')).toBe(false);
  });

  it('is true for a past date and false for a future one', () => {
    expect(isPast(new Date(Date.now() - DAY_MS).toISOString())).toBe(true);
    expect(isPast(new Date(Date.now() + DAY_MS).toISOString())).toBe(false);
  });
});

describe('formatDueDate', () => {
  it('returns an em dash for null, undefined or an invalid date', () => {
    expect(formatDueDate(null)).toBe('—');
    expect(formatDueDate(undefined)).toBe('—');
    expect(formatDueDate('not-a-date')).toBe('—');
  });

  it('phrases an upcoming deadline as "due in N days"', () => {
    expect(formatDueDate(new Date(Date.now() + 3 * DAY_MS + 60_000).toISOString())).toBe(
      'due in 3 days',
    );
  });

  it('phrases a passed deadline as "overdue by N days"', () => {
    expect(formatDueDate(new Date(Date.now() - 2 * DAY_MS - 60_000).toISOString())).toBe(
      'overdue by 2 days',
    );
  });

  it('reports the same day as "due today"', () => {
    expect(formatDueDate(new Date(Date.now() + 60_000).toISOString())).toBe('due today');
  });

  it('falls back to an absolute date beyond 30 days', () => {
    expect(formatDueDate(new Date(Date.now() + 200 * DAY_MS).toISOString())).toMatch(/^due /);
    expect(formatDueDate(new Date(Date.now() - 200 * DAY_MS).toISOString())).toMatch(/^was due /);
  });
});
