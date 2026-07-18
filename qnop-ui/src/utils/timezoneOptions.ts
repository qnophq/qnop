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

/** A selectable IANA time zone with a human label and its live UTC offset. */
export interface TimezoneOption {
  /** IANA id, e.g. `Europe/Berlin`. */
  zone: string;
  /** Display label — the id with underscores relaxed, e.g. `Europe/Berlin`. */
  label: string;
  /** The area segment before the slash, for grouping, e.g. `Europe`. */
  region: string;
  /** The current short offset, e.g. `GMT+2`. */
  offset: string;
  /** Offset in minutes east of UTC, for sorting. */
  offsetMinutes: number;
}

/** A compact fallback set for the rare engine without {@code Intl.supportedValuesOf}. */
const FALLBACK_ZONES = [
  'UTC',
  'Europe/London',
  'Europe/Berlin',
  'Europe/Paris',
  'Europe/Madrid',
  'Europe/Moscow',
  'America/New_York',
  'America/Chicago',
  'America/Denver',
  'America/Los_Angeles',
  'America/Sao_Paulo',
  'Asia/Dubai',
  'Asia/Kolkata',
  'Asia/Shanghai',
  'Asia/Tokyo',
  'Australia/Sydney',
];

function supportedZones(): string[] {
  const supportedValuesOf = (
    Intl as unknown as { supportedValuesOf?: (key: 'timeZone') => string[] }
  ).supportedValuesOf;
  try {
    return supportedValuesOf ? supportedValuesOf('timeZone') : FALLBACK_ZONES;
  } catch {
    return FALLBACK_ZONES;
  }
}

/** The current short offset label for a zone (e.g. `GMT+2`), or `''` if the zone is unknown. */
export function zoneOffsetLabel(zone: string, at: Date = new Date()): string {
  try {
    const part = new Intl.DateTimeFormat('en-GB', { timeZone: zone, timeZoneName: 'shortOffset' })
      .formatToParts(at)
      .find((p) => p.type === 'timeZoneName');
    // Normalise "GMT" (UTC) to a signed form for consistent chips.
    return part ? part.value.replace(/^GMT$/, 'GMT+0') : '';
  } catch {
    return '';
  }
}

/** Offset in minutes east of UTC, parsed from the short offset label. */
function offsetMinutes(offset: string): number {
  const match = /GMT([+-])(\d{1,2})(?::(\d{2}))?/.exec(offset);
  if (!match) return 0;
  const sign = match[1] === '-' ? -1 : 1;
  const hours = Number(match[2]);
  const minutes = match[3] ? Number(match[3]) : 0;
  return sign * (hours * 60 + minutes);
}

/**
 * All selectable time zones, each with its current offset, sorted west-to-east
 * and then by name. {@code extra} is folded in so a stored value the engine no
 * longer lists still appears as the current selection.
 */
export function buildTimezoneOptions(extra?: string | null): TimezoneOption[] {
  const now = new Date();
  const zones = new Set(supportedZones());
  zones.add('UTC');
  if (extra) zones.add(extra);

  return Array.from(zones)
    .map((zone) => {
      const offset = zoneOffsetLabel(zone, now);
      return {
        zone,
        label: zone.replace(/_/g, ' '),
        region: zone.includes('/') ? zone.slice(0, zone.indexOf('/')) : zone,
        offset,
        offsetMinutes: offsetMinutes(offset),
      };
    })
    .sort((a, b) => a.offsetMinutes - b.offsetMinutes || a.zone.localeCompare(b.zone));
}
