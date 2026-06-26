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
import { isEmail, isHttpUrl, isInteger, isIntegerInRange, isPort, isPresent } from './validation';

describe('validation', () => {
  it('isPresent ignores surrounding whitespace', () => {
    expect(isPresent('x')).toBe(true);
    expect(isPresent('  ')).toBe(false);
    expect(isPresent('')).toBe(false);
  });

  it('isHttpUrl accepts http/https and rejects everything else', () => {
    expect(isHttpUrl('https://qnop.example')).toBe(true);
    expect(isHttpUrl('http://localhost:8080')).toBe(true);
    expect(isHttpUrl('ftp://qnop.example')).toBe(false);
    expect(isHttpUrl('not a url')).toBe(false);
    expect(isHttpUrl('qnop.example')).toBe(false);
  });

  it('isEmail accepts a dotted address and rejects malformed input', () => {
    expect(isEmail('no-reply@qnop.example')).toBe(true);
    expect(isEmail('a@b.co')).toBe(true);
    expect(isEmail('not-an-email')).toBe(false);
    expect(isEmail('a@b')).toBe(false);
    expect(isEmail('a b@c.d')).toBe(false);
  });

  it('isInteger accepts signed integers only', () => {
    expect(isInteger('42')).toBe(true);
    expect(isInteger('-3')).toBe(true);
    expect(isInteger('4.5')).toBe(false);
    expect(isInteger('abc')).toBe(false);
    expect(isInteger('')).toBe(false);
  });

  it('isIntegerInRange enforces inclusive bounds', () => {
    expect(isIntegerInRange('1', 1, 10)).toBe(true);
    expect(isIntegerInRange('10', 1, 10)).toBe(true);
    expect(isIntegerInRange('0', 1, 10)).toBe(false);
    expect(isIntegerInRange('11', 1, 10)).toBe(false);
    expect(isIntegerInRange('x', 1, 10)).toBe(false);
  });

  it('isPort accepts 1–65535', () => {
    expect(isPort('587')).toBe(true);
    expect(isPort('65535')).toBe(true);
    expect(isPort('0')).toBe(false);
    expect(isPort('70000')).toBe(false);
  });
});
