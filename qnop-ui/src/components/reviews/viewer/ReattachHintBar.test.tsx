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
import { buildTheme } from '../../../theme/theme';
import { ReattachHintBar } from './ReattachHintBar';

function renderBar(excerpt: string | null, onCancel = vi.fn()) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <ReattachHintBar excerpt={excerpt} onCancel={onCancel} />
    </ThemeProvider>,
  );
  return onCancel;
}

describe('ReattachHintBar (issue #457)', () => {
  it('names the passage being re-homed and cancels on the X', () => {
    const onCancel = renderBar('the moved clause');

    expect(screen.getByTestId('reattach-hint')).toHaveTextContent(
      'Placing “the moved clause” — select the new passage',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Cancel re-attaching' }));
    expect(onCancel).toHaveBeenCalled();
  });

  it('falls back to a generic prompt for region-only annotations', () => {
    renderBar(null);
    expect(screen.getByTestId('reattach-hint')).toHaveTextContent(
      'Placing the annotation — select its new location',
    );
  });

  it('truncates a long excerpt so the pill stays a pill', () => {
    renderBar('x'.repeat(80));
    expect(screen.getByTestId('reattach-hint')).toHaveTextContent(`${'x'.repeat(60)}…`);
  });
});
