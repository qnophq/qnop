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
import { buildTheme } from './theme';
import { tokens } from './tokens';

describe('buildTheme', () => {
  it('builds a light theme from the devtank42 light surfaces', () => {
    const theme = buildTheme('light');
    expect(theme.palette.mode).toBe('light');
    // Interactive primary is the accessible action blue, not the bright accent.
    expect(theme.palette.primary.main).toBe(tokens.brand.bluePress);
    expect(theme.palette.background.default).toBe(tokens.light.bg);
    expect(theme.palette.background.paper).toBe(tokens.light.surface);
    expect(theme.palette.text.primary).toBe(tokens.light.fg);
    expect(theme.palette.divider).toBe(tokens.light.border);
  });

  it('builds a dark theme from the devtank42 dark surfaces', () => {
    const theme = buildTheme('dark');
    expect(theme.palette.mode).toBe('dark');
    expect(theme.palette.background.default).toBe(tokens.dark.bg);
    expect(theme.palette.background.paper).toBe(tokens.dark.surface);
    expect(theme.palette.text.primary).toBe(tokens.dark.fg);
  });

  it('maps the semantic colours (AA-strong tones for white-on-colour buttons)', () => {
    const theme = buildTheme('light');
    expect(theme.palette.success.main).toBe(tokens.semantic.successStrong);
    expect(theme.palette.warning.main).toBe(tokens.semantic.warning);
    expect(theme.palette.error.main).toBe(tokens.semantic.dangerStrong);
  });

  it('keeps the bright brand blue available as an accent for recognition', () => {
    const theme = buildTheme('light');
    expect(theme.qnop.brand.blue).toBe('#1292EE');
    expect(theme.palette.primary.main).not.toBe(theme.qnop.brand.blue);
  });

  it('uses the squircle radius and the Outfit display face', () => {
    const theme = buildTheme('light');
    expect(theme.shape.borderRadius).toBe(tokens.radius.md);
    expect(theme.typography.fontFamily).toContain('Outfit');
    expect(theme.typography.h1.fontFamily).toContain('Outfit');
  });

  it('exposes the qnop custom tokens for both modes', () => {
    const light = buildTheme('light');
    const dark = buildTheme('dark');
    expect(light.qnop.mode).toBe('light');
    expect(light.qnop.surface2).toBe(tokens.light.surface2);
    expect(dark.qnop.surface2).toBe(tokens.dark.surface2);
    expect(light.qnop.focusRing).toBe(tokens.shadow.focusRing);
    expect(light.qnop.avatarPalette).toHaveLength(8);
    expect(light.qnop.badge.blue.bg).toBe(tokens.badge.blue.bg);
  });
});
