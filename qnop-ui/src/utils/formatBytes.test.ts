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
import { formatBytes } from './formatBytes';

describe('formatBytes', () => {
  it('formats zero and whole bytes without a decimal', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(512)).toBe('512 B');
  });

  it('scales through KB, MB, GB and TB with one decimal', () => {
    expect(formatBytes(1024)).toBe('1 KB');
    expect(formatBytes(2560)).toBe('2.5 KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5 MB');
    expect(formatBytes(1.5 * 1024 * 1024 * 1024)).toBe('1.5 GB');
    expect(formatBytes(3 * 1024 ** 4)).toBe('3 TB');
  });

  it('renders an em dash for absent or invalid values', () => {
    expect(formatBytes(null)).toBe('—');
    expect(formatBytes(undefined)).toBe('—');
    expect(formatBytes(Number.NaN)).toBe('—');
    expect(formatBytes(-1)).toBe('—');
  });
});
