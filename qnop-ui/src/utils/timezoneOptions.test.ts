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

describe('formatter reuse (issue #716)', () => {
  /**
   * Counts `Intl.DateTimeFormat` constructions while `run` executes. Construction
   * — not formatting — is what made the picker expensive: ~49 ms of a ~108 ms
   * build across the engine's ~418 zones.
   */
  function countConstructions(run: () => void): number {
    const original = Intl.DateTimeFormat;
    let constructions = 0;
    const spy = new Proxy(original, {
      construct(target, args: [string?, Intl.DateTimeFormatOptions?]) {
        constructions += 1;
        return new target(...args);
      },
      apply(target, _thisArg, args: [string?, Intl.DateTimeFormatOptions?]) {
        constructions += 1;
        return target(...args);
      },
    });
    Intl.DateTimeFormat = spy;
    try {
      run();
    } finally {
      Intl.DateTimeFormat = original;
    }
    return constructions;
  }

  it('constructs no formatter on a rebuild — the cost is paid once per zone', () => {
    buildTimezoneOptions(); // warm the cache, whatever the engine lists

    const constructions = countConstructions(() => buildTimezoneOptions());

    // The list itself is still rebuilt (so offsets stay live); only the
    // formatters survive, which is where the time went.
    expect(constructions).toBe(0);
  });

  it('reuses one formatter across repeated reads of the same zone', () => {
    zoneOffsetLabel('Europe/Berlin');

    const constructions = countConstructions(() => {
      zoneOffsetLabel('Europe/Berlin');
      zoneOffsetLabel('Europe/Berlin');
    });

    expect(constructions).toBe(0);
  });

  it('keeps offsets live across instants — a cached formatter carries no date', () => {
    // The reason this caches formatters and not the finished list: a formatter
    // is date-independent, so a zone that shifts with DST still reads correctly
    // for any instant. New York is GMT-4 in July and GMT-5 in January.
    const july = new Date('2026-07-01T12:00:00Z');
    const january = new Date('2026-01-01T12:00:00Z');

    expect(zoneOffsetLabel('America/New_York', july)).toBe('GMT-4');
    expect(zoneOffsetLabel('America/New_York', january)).toBe('GMT-5');
    // …and back again, proving the first read did not pin the offset.
    expect(zoneOffsetLabel('America/New_York', july)).toBe('GMT-4');
  });

  it('does not cache a zone the engine rejects', () => {
    expect(zoneOffsetLabel('Nowhere/Void')).toBe('');

    // A rejected zone throws in the constructor, so nothing lands in the cache
    // and a later call retries rather than serving a poisoned entry — which is
    // also why junk input cannot grow the map without bound.
    const constructions = countConstructions(() => {
      expect(zoneOffsetLabel('Nowhere/Void')).toBe('');
    });

    expect(constructions).toBe(1);
  });
});
