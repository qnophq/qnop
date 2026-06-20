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

import { createTheme, type Theme } from '@mui/material/styles';
import type { PaletteMode } from '@mui/material';
import { tokens } from './tokens';

/**
 * Builds the MUI theme for the given mode. This is the minimal foundation
 * theme (#100): brand primary + light/dark surfaces and a few sensible
 * defaults. The full devtank42 system (Sklow font, the complete type scale,
 * component overrides) is layered on in #101.
 */
export function buildTheme(mode: PaletteMode): Theme {
  const isDark = mode === 'dark';
  return createTheme({
    palette: {
      mode,
      primary: { main: tokens.color.blue, dark: tokens.color.bluePress },
      secondary: { main: tokens.color.navy },
      success: { main: tokens.color.success },
      warning: { main: tokens.color.warning },
      error: { main: tokens.color.danger },
      background: {
        default: isDark ? tokens.color.navy : tokens.color.offWhite,
        paper: isDark ? tokens.color.navy700 : tokens.color.white,
      },
      text: {
        primary: isDark ? tokens.color.white : tokens.color.navy,
        secondary: isDark ? '#B9C6D4' : tokens.color.gray600,
      },
    },
    typography: {
      fontFamily: '"Inter", system-ui, -apple-system, "Segoe UI", sans-serif',
      h1: { fontWeight: 700, letterSpacing: '-0.025em' },
      h6: { fontWeight: 600, letterSpacing: '-0.01em' },
      button: { textTransform: 'none', fontWeight: 600 },
    },
    shape: { borderRadius: tokens.radius.md },
  });
}
