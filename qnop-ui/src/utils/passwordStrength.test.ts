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
import {
  GENERATED_PASSWORD_LENGTH,
  generateStrongPassword,
  passwordStrength,
} from './passwordStrength';

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
    expect(r.label).toBe('Strong');
    expect(r.acceptable).toBe(true);
  });
});

describe('generateStrongPassword', () => {
  it('uses the default length and honours a custom length', () => {
    expect(generateStrongPassword()).toHaveLength(GENERATED_PASSWORD_LENGTH);
    expect(generateStrongPassword(32)).toHaveLength(32);
  });

  it('always produces a password the strength meter rates as strong', () => {
    for (let i = 0; i < 200; i++) {
      const r = passwordStrength(generateStrongPassword());
      expect(r.acceptable).toBe(true);
      expect(r.score).toBe(4);
    }
  });

  it('contains every required character class and no ambiguous glyphs', () => {
    for (let i = 0; i < 200; i++) {
      const pw = generateStrongPassword();
      expect(pw).toMatch(/[a-z]/);
      expect(pw).toMatch(/[A-Z]/);
      expect(pw).toMatch(/[0-9]/);
      expect(pw).toMatch(/[^A-Za-z0-9]/);
      expect(pw).not.toMatch(/[0O1lI]/);
    }
  });

  it('returns a different password on each call', () => {
    const seen = new Set(Array.from({ length: 500 }, () => generateStrongPassword()));
    expect(seen.size).toBe(500);
  });
});
