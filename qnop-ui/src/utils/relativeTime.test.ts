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
import { shortRelativeTime } from './relativeTime';

const NOW = new Date('2026-07-05T12:00:00Z');

describe('shortRelativeTime', () => {
  it('speaks the social-feed dialect', () => {
    expect(shortRelativeTime('2026-07-05T11:59:30Z', NOW)).toBe('now');
    expect(shortRelativeTime('2026-07-05T11:55:00Z', NOW)).toBe('5m');
    expect(shortRelativeTime('2026-07-05T09:00:00Z', NOW)).toBe('3h');
    expect(shortRelativeTime('2026-07-03T12:00:00Z', NOW)).toBe('2d');
  });

  it('falls back to the calendar date after a week', () => {
    expect(shortRelativeTime('2026-06-20T12:00:00Z', NOW)).toBe('Jun 20');
    expect(shortRelativeTime('2025-12-24T12:00:00Z', NOW)).toBe('Dec 24, 2025');
  });

  it('treats future timestamps (clock skew) as now', () => {
    expect(shortRelativeTime('2026-07-05T12:00:30Z', NOW)).toBe('now');
  });
});
