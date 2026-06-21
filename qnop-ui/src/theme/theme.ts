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

import { createTheme, type Theme, type ThemeOptions } from '@mui/material/styles';
import type { PaletteMode, Shadows } from '@mui/material';
import { surfaces, tokens } from './tokens';

/**
 * Brand tokens that have no native MUI slot, exposed on the theme as `theme.qnop`
 * so components and `sx` can read them type-safely (badge tones, the raised
 * surface colour, motion easings, the focus ring). Surfaced via module
 * augmentation below.
 */
export interface QnopTokens {
  mode: PaletteMode;
  /** Slightly raised surface (rows, inputs) — the prototype `--app-surface-2`. */
  surface2: string;
  badge: typeof tokens.badge;
  avatarPalette: typeof tokens.avatarPalette;
  motion: typeof tokens.motion;
  focusRing: string;
  brand: typeof tokens.brand;
}

declare module '@mui/material/styles' {
  interface Theme {
    qnop: QnopTokens;
  }
  interface ThemeOptions {
    qnop?: QnopTokens;
  }
}

/** Flat, crisp elevation scale — the brand reads as bordered surfaces, not shadow stacks. */
function buildShadows(): Shadows {
  const { xs, sm, md, lg } = tokens.shadow;
  const scale = Array.from({ length: 25 }, (_, i) => {
    if (i === 0) return 'none';
    if (i <= 2) return xs;
    if (i <= 4) return sm;
    if (i <= 12) return md;
    return lg;
  });
  return scale as unknown as Shadows;
}

/**
 * Builds the devtank42 MUI theme for the given mode (#101). All values come from
 * {@link tokens}; light and dark use the matching surface set. The same shape is
 * returned for both modes, so `main.tsx` simply rebuilds on a theme toggle.
 */
export function buildTheme(mode: PaletteMode): Theme {
  const s = surfaces[mode];

  const qnop: QnopTokens = {
    mode,
    surface2: s.surface2,
    badge: tokens.badge,
    avatarPalette: tokens.avatarPalette,
    motion: tokens.motion,
    focusRing: tokens.shadow.focusRing,
    brand: tokens.brand,
  };

  const options: ThemeOptions = {
    qnop,
    palette: {
      mode,
      // Interactive primary uses the accessible action blue (white text 5.2:1);
      // the brighter brand blue lives on in the focus ring and brand accents
      // (theme.qnop.brand.blue), so recognition holds while buttons/links pass
      // WCAG AA. success/error likewise use the darkened "strong" tones for
      // white-on-colour buttons; the bright tones remain for status indicators.
      primary: {
        main: tokens.brand.bluePress,
        dark: tokens.brand.blueDeep,
        light: tokens.brand.blue50,
        contrastText: '#FFFFFF',
      },
      secondary: { main: tokens.brand.navy, contrastText: '#FFFFFF' },
      success: { main: tokens.semantic.successStrong, contrastText: '#FFFFFF' },
      warning: { main: tokens.semantic.warning, contrastText: '#3E2E00' },
      error: { main: tokens.semantic.dangerStrong, contrastText: '#FFFFFF' },
      background: { default: s.bg, paper: s.surface },
      text: { primary: s.fg, secondary: s.fg2, disabled: s.fg3 },
      divider: s.border,
    },
    shape: { borderRadius: tokens.radius.md },
    shadows: buildShadows(),
    typography: {
      fontFamily: tokens.font.sans,
      fontSize: 14,
      h1: {
        fontFamily: tokens.font.display,
        fontWeight: 700,
        fontSize: '2rem',
        letterSpacing: '-0.025em',
        lineHeight: 1.1,
      },
      h2: {
        fontFamily: tokens.font.display,
        fontWeight: 700,
        fontSize: '1.625rem',
        letterSpacing: '-0.02em',
        lineHeight: 1.15,
      },
      h3: {
        fontFamily: tokens.font.display,
        fontWeight: 600,
        fontSize: '1.375rem',
        letterSpacing: '-0.02em',
        lineHeight: 1.2,
      },
      h4: {
        fontFamily: tokens.font.display,
        fontWeight: 600,
        fontSize: '1.125rem',
        letterSpacing: '-0.01em',
      },
      h5: {
        fontFamily: tokens.font.display,
        fontWeight: 600,
        fontSize: '1rem',
        letterSpacing: '-0.01em',
      },
      h6: {
        fontFamily: tokens.font.display,
        fontWeight: 600,
        fontSize: '0.9375rem',
        letterSpacing: '-0.01em',
      },
      body1: { fontSize: '0.9375rem', letterSpacing: '-0.005em' },
      body2: { fontSize: '0.8125rem', letterSpacing: '-0.005em' },
      button: { textTransform: 'none', fontWeight: 500, letterSpacing: '-0.005em' },
      caption: { fontSize: '0.6875rem' },
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          a: { color: tokens.brand.blue, textDecoration: 'none' },
          '::selection': { background: tokens.brand.blue, color: '#FFFFFF' },
          '*::-webkit-scrollbar': { width: 10, height: 10 },
          '*::-webkit-scrollbar-thumb': {
            background: s.borderStrong,
            borderRadius: tokens.radius.pill,
            border: '2px solid transparent',
            backgroundClip: 'content-box',
          },
        },
      },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: {
            borderRadius: tokens.radius.sm + 2,
            fontWeight: 500,
            '&:active': { transform: 'translateY(1px)' },
          },
          sizeSmall: { height: 28, paddingInline: 10, fontSize: '0.75rem' },
          sizeMedium: { height: 34, paddingInline: 14 },
          sizeLarge: { height: 40, paddingInline: 18, fontSize: '0.875rem' },
        },
      },
      MuiPaper: {
        defaultProps: { elevation: 0 },
        styleOverrides: { root: { backgroundImage: 'none' } },
      },
      MuiCard: {
        defaultProps: { elevation: 0 },
        styleOverrides: {
          root: { borderRadius: tokens.radius.lg, border: `1px solid ${s.border}` },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: { borderRadius: tokens.radius.sm, fontWeight: 500 },
        },
      },
      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            borderRadius: tokens.radius.sm + 2,
            '&.Mui-focused .MuiOutlinedInput-notchedOutline': {
              borderColor: tokens.brand.blue,
              boxShadow: tokens.shadow.focusRing,
            },
          },
        },
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: {
            background: tokens.brand.navy,
            fontSize: '0.75rem',
            borderRadius: tokens.radius.sm,
          },
        },
      },
    },
  };

  return createTheme(options);
}
