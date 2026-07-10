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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../../theme/theme';
import { FocusDrawer } from './FocusDrawer';

function renderDrawer() {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <FocusDrawer open onClose={vi.fn()}>
        <div>panel content</div>
      </FocusDrawer>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
});

describe('FocusDrawer resizing (issue #403)', () => {
  it('exposes a keyboard-operable separator that widens and narrows the drawer', () => {
    renderDrawer();
    const handle = screen.getByTestId('focus-drawer-resize-handle');
    const before = Number(handle.getAttribute('aria-valuenow'));

    // The handle sits on the leading edge: ArrowLeft grows the drawer.
    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    expect(Number(handle.getAttribute('aria-valuenow'))).toBe(before + 24);

    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    expect(Number(handle.getAttribute('aria-valuenow'))).toBe(before);
  });

  it('persists the chosen width as a personal preference', () => {
    renderDrawer();
    fireEvent.keyDown(screen.getByTestId('focus-drawer-resize-handle'), { key: 'ArrowLeft' });

    expect(Number(localStorage.getItem('qnop-focus-drawer-width'))).toBeGreaterThan(0);
  });

  it('never resizes below the minimum width', () => {
    localStorage.setItem('qnop-focus-drawer-width', '381');
    renderDrawer();
    const handle = screen.getByTestId('focus-drawer-resize-handle');

    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    fireEvent.keyDown(handle, { key: 'ArrowRight' });

    expect(Number(handle.getAttribute('aria-valuenow'))).toBe(380);
  });
});
