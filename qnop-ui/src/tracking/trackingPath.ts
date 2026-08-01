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

/** Anything at least this long is treated as an identifier, whatever it looks like. */
const MAX_PLAIN_SEGMENT = 32;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LONG_HEX = /^[0-9a-f]{12,}$/i;
const NUMERIC = /^[0-9]{3,}$/;

function looksLikeIdentifier(segment: string): boolean {
  if (segment === '' || segment.startsWith(':')) {
    return false;
  }
  return (
    segment.length > MAX_PLAIN_SEGMENT ||
    UUID.test(segment) ||
    LONG_HEX.test(segment) ||
    NUMERIC.test(segment)
  );
}

/**
 * Turns the address of the current page into the shape of the page (issue #666).
 *
 * ```
 * /reviews/8f3c…-a1/tasks   +  { documentId: '8f3c…-a1' }   →   /reviews/:documentId/tasks
 * ```
 *
 * The router already knows which segments were parameters — it filled them in — so the honest
 * answer comes from `params` rather than from guessing at the string. A review's id identifies a
 * customer's document, and an analytics report is exactly the wrong place to keep a list of them.
 *
 * Whatever survives that (an id from a route this function has never heard of) still has to get
 * past a shape check, and the query string never travels at all: `?q=merger agreement` is the
 * subject of somebody's document, typed by a human.
 */
export function anonymizePath(
  pathname: string,
  params: Readonly<Record<string, string | undefined>> = {},
): string {
  const named = new Map<string, string>();
  for (const [key, value] of Object.entries(params)) {
    if (!value) {
      continue;
    }
    // A splat ("*") can stand for several segments at once; each is replaced.
    const label = key === '*' ? ':path' : `:${key}`;
    for (const part of value.split('/')) {
      if (part) {
        named.set(part, label);
      }
    }
  }

  const segments = pathname.split('/').map((segment) => {
    const known = named.get(segment);
    if (known) {
      return known;
    }
    return looksLikeIdentifier(segment) ? ':id' : segment;
  });

  const path = segments.join('/');
  return path === '' ? '/' : path;
}
