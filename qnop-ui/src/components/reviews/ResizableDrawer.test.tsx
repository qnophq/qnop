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
import { buildTheme } from '../../theme/theme';
import { ResizableDrawer } from './ResizableDrawer';

function renderDrawer(overrides: Record<string, unknown> = {}) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <ResizableDrawer
        open
        onClose={vi.fn()}
        storageKey="qnop-test-drawer-width"
        defaultWidth={500}
        handleAriaLabel="Resize the test drawer"
        handleTestId="test-drawer-resize-handle"
        {...overrides}
      >
        <div>drawer content</div>
      </ResizableDrawer>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
});

describe('ResizableDrawer (issue #403)', () => {
  it('exposes a keyboard-operable separator that widens and narrows the drawer', () => {
    renderDrawer();
    const handle = screen.getByRole('separator', { name: 'Resize the test drawer' });
    const before = Number(handle.getAttribute('aria-valuenow'));

    // The handle sits on the leading edge: ArrowLeft grows the drawer.
    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    expect(Number(handle.getAttribute('aria-valuenow'))).toBe(before + 24);

    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    expect(Number(handle.getAttribute('aria-valuenow'))).toBe(before);
  });

  it('persists the chosen width under its own storage key', () => {
    renderDrawer();
    fireEvent.keyDown(screen.getByTestId('test-drawer-resize-handle'), { key: 'ArrowLeft' });

    expect(Number(localStorage.getItem('qnop-test-drawer-width'))).toBe(524);
  });

  it('starts from the persisted width of ITS surface, not another drawer’s', () => {
    localStorage.setItem('qnop-test-drawer-width', '600');
    localStorage.setItem('qnop-other-drawer-width', '444');
    renderDrawer();

    const handle = screen.getByTestId('test-drawer-resize-handle');
    expect(Number(handle.getAttribute('aria-valuenow'))).toBe(600);
  });

  it('never resizes below the minimum width', () => {
    localStorage.setItem('qnop-test-drawer-width', '381');
    renderDrawer();
    const handle = screen.getByTestId('test-drawer-resize-handle');

    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    fireEvent.keyDown(handle, { key: 'ArrowRight' });

    expect(Number(handle.getAttribute('aria-valuenow'))).toBe(380);
  });
});
