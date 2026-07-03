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
import { holePolygon } from './spotlightModel';

function renderLayer(spotlight: Parameters<typeof FocusScrimLayer>[0]['spotlight']) {
  const onDismiss = vi.fn();
  const view = render(
    <ThemeProvider theme={buildTheme('light')}>
      <FocusScrimLayer spotlight={spotlight} onDismiss={onDismiss} surfaceIndex={0} />
    </ThemeProvider>,
  );
  return { onDismiss, view };
}

describe('holePolygon', () => {
  it('cuts a rectangular notch with a constant vertex count (morphable)', () => {
    const polygon = holePolygon({ x: 0.1, y: 0.2, width: 0.3, height: 0.1 });
    expect(polygon).toContain('10.0000% 20.0000%');
    expect(polygon).toContain('40.0000% 30.0000%');
    // 10 vertices — the same count for every hole, so clip-path can transition.
    expect(polygon.split(',')).toHaveLength(10);
  });
});

describe('FocusScrimLayer', () => {
  it('dims the whole page when the spotlight is elsewhere', () => {
    renderLayer(null);
    const clip = getComputedStyle(screen.getByTestId('focus-scrim-0')).clipPath;
    expect(['', 'none']).toContain(clip);
  });

  it('cuts the sharp hole at the spotlight and moves it with the mark', () => {
    const { view } = renderLayer({ x: 0.1, y: 0.2, width: 0.3, height: 0.1 });
    expect(screen.getByTestId('focus-scrim-0')).toHaveStyle({
      clipPath: holePolygon({ x: 0.1, y: 0.2, width: 0.3, height: 0.1 }),
    });

    view.rerender(
      <ThemeProvider theme={buildTheme('light')}>
        <FocusScrimLayer
          spotlight={{ x: 0.5, y: 0.6, width: 0.2, height: 0.05 }}
          onDismiss={vi.fn()}
          surfaceIndex={0}
        />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('focus-scrim-0')).toHaveStyle({
      clipPath: holePolygon({ x: 0.5, y: 0.6, width: 0.2, height: 0.05 }),
    });
  });

  it('dismisses on a veil click', () => {
    const { onDismiss } = renderLayer({ x: 0.1, y: 0.2, width: 0.3, height: 0.1 });
    fireEvent.click(screen.getByTestId('focus-scrim-0'));
    expect(onDismiss).toHaveBeenCalled();
  });
});
