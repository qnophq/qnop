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

import { beforeEach, describe, expect, it } from 'vitest';
import { useUiStore } from './uiStore';

describe('uiStore', () => {
  beforeEach(() => {
    localStorage.clear();
    useUiStore.setState({ themeMode: 'light' });
  });

  it('toggles between light and dark and persists the choice', () => {
    useUiStore.getState().toggleTheme();
    expect(useUiStore.getState().themeMode).toBe('dark');
    expect(localStorage.getItem('qnop-theme')).toBe('dark');

    useUiStore.getState().toggleTheme();
    expect(useUiStore.getState().themeMode).toBe('light');
    expect(localStorage.getItem('qnop-theme')).toBe('light');
  });

  it('setThemeMode sets an explicit mode', () => {
    useUiStore.getState().setThemeMode('dark');
    expect(useUiStore.getState().themeMode).toBe('dark');
  });
});
