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

/**
 * Small, dependency-free field validators shared across the admin forms. Each
 * is a pure predicate over a raw string; the forms compose them into per-field
 * error messages. The email/URL/port rules mirror the server's ValueValidator so
 * client and server agree on what "valid" means.
 */

/** True if the trimmed value is non-empty. */
export function isPresent(value: string): boolean {
  return value.trim().length > 0;
}

/** True if the value is a syntactically valid http(s) URL. */
export function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value.trim());
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

/** A pragmatic email check — exactly one `@`, a dotted domain, no spaces. Mirrors the server. */
export function isEmail(value: string): boolean {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value.trim());
}

/** True if the value is an integer (optionally signed). */
export function isInteger(value: string): boolean {
  return /^-?\d+$/.test(value.trim());
}

/** True if the value is an integer within `[min, max]` inclusive. */
export function isIntegerInRange(value: string, min: number, max: number): boolean {
  if (!isInteger(value)) {
    return false;
  }
  const parsed = Number(value.trim());
  return parsed >= min && parsed <= max;
}

/** True if the value is a TCP port number (1–65535). */
export function isPort(value: string): boolean {
  return isIntegerInRange(value, 1, 65535);
}
