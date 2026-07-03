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
import { FocusScrimLayer } from './FocusScrimLayer';

function renderLayer(spotlight: Parameters<typeof FocusScrimLayer>[0]['spotlight']) {
  const onDismiss = vi.fn();
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <FocusScrimLayer spotlight={spotlight} onDismiss={onDismiss} surfaceIndex={0} />
    </ThemeProvider>,
  );
  return onDismiss;
}

describe('FocusScrimLayer', () => {
  it('dims the whole page when the spotlight is elsewhere', () => {
    renderLayer(null);
    const layer = screen.getByTestId('focus-scrim-0');
    expect(layer.children).toHaveLength(1);
  });

  it('leaves a sharp hole around the spotlight', () => {
    renderLayer({ x: 0.1, y: 0.2, width: 0.3, height: 0.1 });
    const layer = screen.getByTestId('focus-scrim-0');
    // above, below, left, right — the spotlight itself carries no veil.
    expect(layer.children).toHaveLength(4);
    expect(screen.getByTestId('scrim-segment-0-0')).toHaveStyle({ top: '0%', height: '20%' });
    expect(screen.getByTestId('scrim-segment-0-2')).toHaveStyle({
      top: '20%',
      left: '0%',
      width: '10%',
    });
  });

  it('dismisses on a veil click', () => {
    const onDismiss = renderLayer({ x: 0.1, y: 0.2, width: 0.3, height: 0.1 });
    fireEvent.click(screen.getByTestId('scrim-segment-0-0'));
    expect(onDismiss).toHaveBeenCalled();
  });
});
