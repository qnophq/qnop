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
import { FALLBACK_TIME_ZONE, isValidTimeZone, resolveTimeZone } from './timezone';

describe('isValidTimeZone', () => {
  it('accepts real IANA ids and UTC', () => {
    expect(isValidTimeZone('UTC')).toBe(true);
    expect(isValidTimeZone('Europe/Berlin')).toBe(true);
    expect(isValidTimeZone('Asia/Tokyo')).toBe(true);
  });

  it('rejects blank, nullish, and bogus values', () => {
    expect(isValidTimeZone('')).toBe(false);
    expect(isValidTimeZone(null)).toBe(false);
    expect(isValidTimeZone(undefined)).toBe(false);
    expect(isValidTimeZone('Mars/Olympus_Mons')).toBe(false);
    expect(isValidTimeZone('not a zone')).toBe(false);
  });
});

describe('resolveTimeZone fallback chain', () => {
  it('prefers a valid user profile zone', () => {
    expect(resolveTimeZone('Europe/Berlin', 'Asia/Tokyo')).toBe('Europe/Berlin');
  });

  it('falls back to the application default when the user has no valid zone', () => {
    expect(resolveTimeZone(undefined, 'Asia/Tokyo')).toBe('Asia/Tokyo');
    expect(resolveTimeZone('', 'Asia/Tokyo')).toBe('Asia/Tokyo');
    expect(resolveTimeZone('bogus/zone', 'Asia/Tokyo')).toBe('Asia/Tokyo');
  });

  it('falls back to UTC when neither is a valid zone', () => {
    expect(resolveTimeZone(null, null)).toBe(FALLBACK_TIME_ZONE);
    expect(resolveTimeZone('bogus', 'also-bogus')).toBe('UTC');
  });
});
