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
import { normalizeCadence } from './reviewMailCadence';

describe('normalizeCadence (#680)', () => {
  it('keeps an explicit opt-out an opt-out', () => {
    // The one outcome the change must not have: somebody who had said no
    // quietly moving to a daily mail.
    expect(normalizeCadence('false')).toBe('OFF');
    expect(normalizeCadence('FALSE')).toBe('OFF');
  });

  it('maps the legacy opt-in the way migration 0035 does', () => {
    expect(normalizeCadence('true')).toBe('DAILY');
  });

  it('reads the current values back', () => {
    expect(normalizeCadence('IMMEDIATE')).toBe('IMMEDIATE');
    expect(normalizeCadence(' daily ')).toBe('DAILY');
    expect(normalizeCadence('OFF')).toBe('OFF');
  });

  it('falls back to the registry default when nothing is stored', () => {
    expect(normalizeCadence(undefined)).toBe('DAILY');
    expect(normalizeCadence('weekly')).toBe('DAILY');
  });
});
