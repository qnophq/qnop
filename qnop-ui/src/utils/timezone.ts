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

/** The per-user setting key (io.qnop.service.UserSettingKey.TIMEZONE) carrying the IANA zone id. */
export const TIMEZONE_SETTING_KEY = 'timezone';

/** The last-resort display zone when neither the user nor the operator configured one. */
export const FALLBACK_TIME_ZONE = 'UTC';

/** Whether a string is a usable IANA time-zone id, so a bad value can never break rendering. */
export function isValidTimeZone(zone: string | null | undefined): zone is string {
  if (!zone) return false;
  try {
    new Intl.DateTimeFormat('en', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

/**
 * Resolves the active display timezone (issue #465, ADR-0041): the user's profile
 * preference, else the application default, else UTC. Each candidate must be a
 * valid IANA id to be used; a blank or invalid value is skipped.
 */
export function resolveTimeZone(
  userZone: string | null | undefined,
  appDefaultZone: string | null | undefined,
): string {
  if (isValidTimeZone(userZone)) return userZone;
  if (isValidTimeZone(appDefaultZone)) return appDefaultZone;
  return FALLBACK_TIME_ZONE;
}
