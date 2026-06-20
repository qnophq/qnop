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

import { create } from 'zustand';
import type { PaletteMode } from '@mui/material';

const THEME_KEY = 'qnop-theme';

function initialThemeMode(): PaletteMode {
  try {
    const stored = localStorage.getItem(THEME_KEY);
    if (stored === 'light' || stored === 'dark') {
      return stored;
    }
    if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
      return 'dark';
    }
  } catch {
    // localStorage / matchMedia unavailable (e.g. SSR or locked-down env).
  }
  return 'light';
}

interface UiState {
  themeMode: PaletteMode;
  setThemeMode: (mode: PaletteMode) => void;
  toggleTheme: () => void;
}

export const useUiStore = create<UiState>((set, get) => ({
  themeMode: initialThemeMode(),
  setThemeMode: (mode) => {
    try {
      localStorage.setItem(THEME_KEY, mode);
    } catch {
      // Persistence is best-effort.
    }
    set({ themeMode: mode });
  },
  toggleTheme: () => get().setThemeMode(get().themeMode === 'dark' ? 'light' : 'dark'),
}));
