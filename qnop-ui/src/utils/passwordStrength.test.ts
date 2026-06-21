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
import { passwordStrength } from './passwordStrength';

describe('passwordStrength', () => {
  it('scores an empty password as 0 and not acceptable', () => {
    const r = passwordStrength('');
    expect(r.score).toBe(0);
    expect(r.acceptable).toBe(false);
    expect(r.label).toBe('');
  });

  it('marks a short password as not acceptable', () => {
    expect(passwordStrength('Ab1!').acceptable).toBe(false);
  });

  it('accepts an 8+ char password', () => {
    expect(passwordStrength('abcdefgh').acceptable).toBe(true);
  });

  it('scores a long mixed password as strong', () => {
    const r = passwordStrength('Str0ng#Pass');
    expect(r.score).toBe(4);
    expect(r.label).toBe('Stark');
    expect(r.acceptable).toBe(true);
  });
});
