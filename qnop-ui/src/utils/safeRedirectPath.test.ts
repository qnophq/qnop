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
import { safeRedirectPath } from './safeRedirectPath';

describe('safeRedirectPath', () => {
  it('keeps a same-origin relative path', () => {
    expect(safeRedirectPath('/reviews/42')).toBe('/reviews/42');
  });

  it('falls back for null/empty', () => {
    expect(safeRedirectPath(null)).toBe('/');
    expect(safeRedirectPath('')).toBe('/');
    expect(safeRedirectPath(undefined)).toBe('/');
  });

  it('rejects absolute and protocol-relative URLs (open-redirect guard)', () => {
    expect(safeRedirectPath('https://evil.example/phish')).toBe('/');
    expect(safeRedirectPath('//evil.example')).toBe('/');
  });

  it('honours a custom fallback', () => {
    expect(safeRedirectPath(null, '/home')).toBe('/home');
  });
});
