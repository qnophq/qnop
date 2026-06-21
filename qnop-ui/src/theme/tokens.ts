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

/**
 * The devtank42 design tokens — the single source of truth for the qnop UI
 * (#101), ported verbatim from the design prototype
 * (docs/qnop-design-prototype/assets/{colors_and_type,styles}.css). The MUI
 * theme (theme.ts) is built from these; nothing else should hardcode brand
 * values. `light`/`dark` carry the per-mode surface, text and border sets.
 */

const brand = {
  blue: '#1292EE',
  blueHover: '#0F80D6',
  bluePress: '#0B6FBC',
  blue50: '#E7F4FE',
  navy: '#012142',
  navy700: '#02305E',
} as const;

const semantic = {
  success: '#16B77B',
  warning: '#F5B83D',
  danger: '#E5484D',
} as const;

/** Per-mode surfaces, foregrounds and borders (prototype `--app-*` layer). */
const light = {
  bg: '#F6F8FB',
  surface: '#FFFFFF',
  surface2: '#F6F8FB',
  fg: '#012142',
  fg2: '#3B4958',
  fg3: '#778797',
  border: '#E6EBF1',
  borderStrong: '#CBD4DF',
} as const;

const dark = {
  bg: '#0A1828',
  surface: '#0F2340',
  surface2: '#132A4A',
  fg: '#EAF1FA',
  fg2: '#B9C6D4',
  fg3: '#7A8BA0',
  border: 'rgba(255,255,255,0.08)',
  borderStrong: 'rgba(255,255,255,0.14)',
} as const;

/** Pill/badge tones (background, text, border) — `fgDark` overrides text in dark mode. */
const badge = {
  blue: { bg: '#E7F4FE', fg: '#085B9C', border: 'rgba(18,144,239,0.18)', fgDark: '#9BCEFA' },
  green: {
    bg: 'rgba(22,183,123,0.10)',
    fg: '#087E53',
    border: 'rgba(22,183,123,0.22)',
    fgDark: '#4FD9A3',
  },
  amber: {
    bg: 'rgba(245,184,61,0.14)',
    fg: '#8A5E00',
    border: 'rgba(245,184,61,0.28)',
    fgDark: '#F5B83D',
  },
  red: {
    bg: 'rgba(229,72,77,0.10)',
    fg: '#B32027',
    border: 'rgba(229,72,77,0.22)',
    fgDark: '#FB858A',
  },
} as const;

/** Deterministic avatar palette (prototype `.av-a`…`.av-h`). */
const avatarPalette = [
  '#1292EE',
  '#055396',
  '#16B77B',
  '#F5B83D',
  '#E5484D',
  '#6B54E5',
  '#0F80D6',
  '#3B4958',
] as const;

const radius = { xs: 4, sm: 6, md: 10, lg: 16, xl: 24, pill: 999 } as const;

const shadow = {
  xs: '0 1px 2px rgba(1,32,66,0.06)',
  sm: '0 2px 6px rgba(1,32,66,0.08)',
  md: '0 8px 24px rgba(1,32,66,0.10)',
  lg: '0 16px 48px rgba(1,32,66,0.14)',
  focusRing: '0 0 0 3px rgba(18,144,239,0.32)',
} as const;

const motion = {
  easeOut: 'cubic-bezier(0.22, 1, 0.36, 1)',
  easeInOut: 'cubic-bezier(0.65, 0, 0.35, 1)',
  durFast: 120,
  durBase: 200,
  durSlow: 360,
} as const;

const font = {
  sans: '"Outfit", system-ui, -apple-system, "Segoe UI", sans-serif',
  display: '"Outfit", system-ui, -apple-system, "Segoe UI", sans-serif',
  mono: '"JetBrains Mono", "SF Mono", Menlo, Consolas, monospace',
} as const;

export const tokens = {
  brand,
  semantic,
  light,
  dark,
  badge,
  avatarPalette,
  radius,
  shadow,
  motion,
  font,
} as const;

/** The surface/text/border set for a given mode. */
export const surfaces = { light, dark } as const;
