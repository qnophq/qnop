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
import { render } from '@testing-library/react';
import IconButton from '@mui/material/IconButton';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from './theme';
import { pseudoBeforeRule } from '../test/cssRules';
import { TOUCH_TARGET_PX, touchTargetSx } from './touchTarget';

describe('touchTargetSx (#724)', () => {
  it('asks for a 44 px minimum', () => {
    expect(TOUCH_TARGET_PX).toBe(44);
  });

  it('emits a centred ::before overlay of at least 44×44 px on a small icon button', () => {
    const { getByRole } = render(
      <ThemeProvider theme={buildTheme('light')}>
        <IconButton size="small" aria-label="Probe" sx={touchTargetSx} />
      </ThemeProvider>,
    );

    const rule = pseudoBeforeRule(getByRole('button', { name: 'Probe' }));

    expect(rule).toBeDefined();
    expect(rule).toContain('position:absolute');
    expect(rule).toContain('width:max(100%, 44px)');
    expect(rule).toContain('height:max(100%, 44px)');
    expect(rule).toContain('translate(-50%, -50%)');
  });
});
