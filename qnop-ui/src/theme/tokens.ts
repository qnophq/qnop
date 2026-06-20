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
 * Starter design tokens for the qnop UI. This is the foundation seam (#100): a
 * small, intentional subset of the devtank42 brand (navy + technical blue) so
 * the app is in-brand from day one. The full design system — Sklow typography,
 * the complete scale, semantic surfaces, light + dark — lands in #101, which
 * fills this file and `theme.ts` out without changing their shape.
 */
export const tokens = {
  color: {
    // devtank42 brand (sampled from the design prototype foundations).
    blue: '#1292EE',
    blueHover: '#0F80D6',
    bluePress: '#0B6FBC',
    blue50: '#E7F4FE',
    navy: '#012142',
    navy700: '#02305E',
    white: '#FFFFFF',
    offWhite: '#F6F8FB',
    smoke: '#EEF2F7',
    gray100: '#E6EBF1',
    gray200: '#CBD4DF',
    gray400: '#778797',
    gray600: '#3B4958',
    success: '#16B77B',
    warning: '#F5B83D',
    danger: '#E5484D',
  },
  radius: {
    sm: 6,
    md: 10,
    lg: 16,
  },
} as const;
