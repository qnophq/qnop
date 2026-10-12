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

import {
  FALLBACK_LOCALE,
  readDeviceLocale,
  writeDeviceLocale,
  type AppLocale,
} from '../i18n/locales';
import { create } from 'zustand';
import type { PaletteMode } from '@mui/material';
import { FALLBACK_TIME_ZONE } from '../utils/timezone';

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
  /**
   * The active display timezone (issue #465, ADR-0041). Held in the store — not read via a query
   * hook at each formatter call site — so every component can format through {@link useFormatters}
   * without depending on a QueryClient. TimezoneSync (mounted once, inside the provider) resolves
   * user profile → application default → UTC and pushes the result here.
   */
  displayTimeZone: string;
  setDisplayTimeZone: (zone: string) => void;
  /**
   * The active UI language (issue #464, ADR-0063), mirrored here by LanguageSync once its catalogs
   * are loaded, so formatters render in it without each depending on a QueryClient.
   */
  locale: AppLocale;
  setLocale: (locale: AppLocale) => void;
  /**
   * The device-wide language chosen on the pre-login pages (localStorage), or `undefined`. It ranks
   * below a signed-in user's stored preference and is never written to the profile by itself.
   */
  deviceLocale: AppLocale | undefined;
  setDeviceLocale: (locale: AppLocale) => void;
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
  displayTimeZone: FALLBACK_TIME_ZONE,
  setDisplayTimeZone: (zone) => set({ displayTimeZone: zone }),
  locale: FALLBACK_LOCALE,
  setLocale: (locale) => set({ locale }),
  deviceLocale: readDeviceLocale(),
  setDeviceLocale: (locale) => {
    writeDeviceLocale(locale);
    set({ deviceLocale: locale });
  },
}));
