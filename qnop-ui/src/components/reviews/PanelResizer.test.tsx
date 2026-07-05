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
import {
  DEFAULT_PANEL_FRACTION,
  PANEL_MAX_FRACTION,
  PANEL_MIN_FRACTION,
  PanelResizer,
} from './PanelResizer';

function renderResizer(fraction = 1 / 3, onFractionChange = vi.fn()) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <PanelResizer fraction={fraction} onFractionChange={onFractionChange} />
    </ThemeProvider>,
  );
  return onFractionChange;
}

describe('PanelResizer', () => {
  it('exposes separator semantics with percent value bounds', () => {
    renderResizer(DEFAULT_PANEL_FRACTION);
    const separator = screen.getByRole('separator', { name: 'Resize annotations panel' });
    expect(separator).toHaveAttribute('aria-valuenow', '33');
    expect(separator).toHaveAttribute('aria-valuemin', '20');
    expect(separator).toHaveAttribute('aria-valuemax', '50');
  });

  it('resizes with the keyboard, clamped to the bounds', () => {
    const onFractionChange = renderResizer(PANEL_MIN_FRACTION);
    const separator = screen.getByRole('separator');

    fireEvent.keyDown(separator, { key: 'ArrowLeft' });
    expect(onFractionChange).toHaveBeenLastCalledWith(PANEL_MIN_FRACTION + 0.02);

    // Shrinking below the minimum clamps.
    fireEvent.keyDown(separator, { key: 'ArrowRight' });
    expect(onFractionChange).toHaveBeenLastCalledWith(PANEL_MIN_FRACTION);

    fireEvent.keyDown(separator, { key: 'Home' });
    expect(onFractionChange).toHaveBeenLastCalledWith(PANEL_MAX_FRACTION);

    fireEvent.keyDown(separator, { key: 'End' });
    expect(onFractionChange).toHaveBeenLastCalledWith(PANEL_MIN_FRACTION);
  });

  it('restores the 2:1 default on double-click', () => {
    const onFractionChange = renderResizer(0.5);
    fireEvent.doubleClick(screen.getByRole('separator'));
    expect(onFractionChange).toHaveBeenCalledWith(DEFAULT_PANEL_FRACTION);
  });

  it('derives the fraction from the pointer inside the split container', () => {
    const onFractionChange = vi.fn();
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <div style={{ display: 'flex', width: 1000 }}>
          <PanelResizer fraction={1 / 3} onFractionChange={onFractionChange} />
        </div>
      </ThemeProvider>,
    );
    const separator = screen.getByRole('separator');
    const container = separator.parentElement as HTMLElement;
    vi.spyOn(container, 'getBoundingClientRect').mockReturnValue({
      right: 1000,
      width: 1000,
    } as DOMRect);

    fireEvent.pointerDown(separator, { button: 0, clientX: 660 });
    // Pointer at x=592: panel = (1000 - 592 - 8) / 1000 = 0.4.
    fireEvent.pointerMove(separator, { clientX: 592 });
    expect(onFractionChange).toHaveBeenLastCalledWith(0.4);

    // Dragging far right clamps at the minimum share.
    fireEvent.pointerMove(separator, { clientX: 990 });
    expect(onFractionChange).toHaveBeenLastCalledWith(PANEL_MIN_FRACTION);
  });
});
