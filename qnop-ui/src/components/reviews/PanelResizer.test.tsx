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

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { PANEL_MAX_WIDTH, PANEL_MIN_WIDTH, PanelResizer } from './PanelResizer';

function renderResizer(width = 400, onWidthChange = vi.fn()) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <PanelResizer width={width} defaultWidth={PANEL_MIN_WIDTH} onWidthChange={onWidthChange} />
    </ThemeProvider>,
  );
  return onWidthChange;
}

describe('PanelResizer', () => {
  it('exposes separator semantics with value bounds', () => {
    renderResizer(400);
    const separator = screen.getByRole('separator', { name: 'Resize annotations panel' });
    expect(separator).toHaveAttribute('aria-valuenow', '400');
    expect(separator).toHaveAttribute('aria-valuemin', String(PANEL_MIN_WIDTH));
    expect(separator).toHaveAttribute('aria-valuemax', String(PANEL_MAX_WIDTH));
  });

  it('resizes with the keyboard, clamped to the bounds', () => {
    const onWidthChange = renderResizer(PANEL_MIN_WIDTH);
    const separator = screen.getByRole('separator');

    fireEvent.keyDown(separator, { key: 'ArrowLeft' });
    expect(onWidthChange).toHaveBeenLastCalledWith(PANEL_MIN_WIDTH + 16);

    // Shrinking below the minimum clamps.
    fireEvent.keyDown(separator, { key: 'ArrowRight' });
    expect(onWidthChange).toHaveBeenLastCalledWith(PANEL_MIN_WIDTH);

    fireEvent.keyDown(separator, { key: 'Home' });
    expect(onWidthChange).toHaveBeenLastCalledWith(PANEL_MAX_WIDTH);

    fireEvent.keyDown(separator, { key: 'End' });
    expect(onWidthChange).toHaveBeenLastCalledWith(PANEL_MIN_WIDTH);
  });

  it('resets to the default width on double-click', () => {
    const onWidthChange = renderResizer(600);
    fireEvent.doubleClick(screen.getByRole('separator'));
    expect(onWidthChange).toHaveBeenCalledWith(PANEL_MIN_WIDTH);
  });
});
