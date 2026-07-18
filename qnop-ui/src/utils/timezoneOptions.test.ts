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
import { buildTimezoneOptions, zoneOffsetLabel } from './timezoneOptions';

describe('buildTimezoneOptions', () => {
  it('always includes UTC', () => {
    const zones = buildTimezoneOptions().map((option) => option.zone);
    expect(zones).toContain('UTC');
  });

  it('folds in an extra zone not otherwise listed (a stored value stays selectable)', () => {
    const options = buildTimezoneOptions('Antarctica/Troll');
    expect(options.map((option) => option.zone)).toContain('Antarctica/Troll');
  });

  it('sorts west-to-east by UTC offset', () => {
    const offsets = buildTimezoneOptions().map((option) => option.offsetMinutes);
    const sorted = [...offsets].sort((a, b) => a - b);
    expect(offsets).toEqual(sorted);
  });

  it('relaxes underscores in the label and exposes the region', () => {
    const berlin = buildTimezoneOptions('Europe/Berlin').find((o) => o.zone === 'Europe/Berlin');
    expect(berlin?.label).toBe('Europe/Berlin');
    expect(berlin?.region).toBe('Europe');
  });
});

describe('zoneOffsetLabel', () => {
  it('returns a GMT-relative label for a real zone', () => {
    expect(zoneOffsetLabel('UTC')).toMatch(/^GMT[+-]\d/);
    expect(zoneOffsetLabel('Asia/Tokyo')).toBe('GMT+9');
  });

  it('returns empty for an unknown zone', () => {
    expect(zoneOffsetLabel('Nowhere/Void')).toBe('');
  });
});
