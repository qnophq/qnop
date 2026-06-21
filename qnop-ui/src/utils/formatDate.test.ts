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
import { formatDateTime, formatRelative } from './formatDate';

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
