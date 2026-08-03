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
 * The three cadences for review mail (issue #680), and how to read a stored value.
 *
 * <p>Its own module rather than living beside the profile screen: a file that exports both
 * components and plain functions breaks fast refresh, and this is needed by a test as well.
 */
export const CADENCE_OPTIONS = [
  { value: 'IMMEDIATE', label: 'As it happens' },
  { value: 'DAILY', label: 'Once a day' },
  { value: 'OFF', label: 'Never' },
] as const;

export const CADENCE_HELP: Record<string, string> = {
  IMMEDIATE: 'One email per event.',
  DAILY: 'One summary each morning, in your timezone. Nothing is sent on a quiet day.',
  OFF: 'No review emails. In-app notifications are unaffected.',
};

/**
 * Reads a stored value, tolerating the booleans this setting held before it became three-valued.
 *
 * <p>The mapping matches migration 0035 exactly — `false` was an explicit opt-out and stays `OFF`,
 * `true` becomes `DAILY` — so a browser holding a response from before the migration still shows
 * what the server will actually do.
 */
export function normalizeCadence(stored: string | undefined): string {
  const value = (stored ?? '').trim().toUpperCase();
  if (value === 'TRUE') return 'DAILY';
  if (value === 'FALSE') return 'OFF';
  return CADENCE_OPTIONS.some((option) => option.value === value) ? value : 'DAILY';
}
