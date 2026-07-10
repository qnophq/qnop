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
import { RegionSelectLayer } from './RegionSelectLayer';

// The surface Box the handlers read via `currentTarget.getBoundingClientRect()`:
// 200×100 anchored at the origin, so clientX/clientY map to unit fractions of
// 200/100. jsdom returns an all-zero rect otherwise (every coordinate NaN).
const RECT = { left: 0, top: 0, width: 200, height: 100 } as DOMRect;

function renderLayer(enabled = true) {
  const onRegionSelected = vi.fn();
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <RegionSelectLayer surfaceIndex={0} enabled={enabled} onRegionSelected={onRegionSelected} />
    </ThemeProvider>,
  );
  const layer = screen.getByTestId('region-layer-0');
  layer.getBoundingClientRect = () => RECT;
  // jsdom leaves pointer capture unimplemented; the drag start calls it.
  layer.setPointerCapture = vi.fn();
  return { layer, onRegionSelected };
}

function drag(layer: HTMLElement, from: [number, number], to: [number, number]) {
  fireEvent.pointerDown(layer, { button: 0, pointerId: 1, clientX: from[0], clientY: from[1] });
  fireEvent.pointerMove(layer, { pointerId: 1, clientX: to[0], clientY: to[1] });
  fireEvent.pointerUp(layer, { pointerId: 1, clientX: to[0], clientY: to[1] });
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('RegionSelectLayer', () => {
  it('normalizes a drag into a page-relative box and reports the drop position', () => {
    const { layer, onRegionSelected } = renderLayer();

    drag(layer, [50, 25], [150, 75]);

    expect(onRegionSelected).toHaveBeenCalledWith(
      0,
      { x: 0.25, y: 0.25, width: 0.5, height: 0.5 },
      { left: 150, top: 75 },
    );
  });

  it('clamps coordinates that fall outside the surface to the unit range', () => {
    const { layer, onRegionSelected } = renderLayer();

    // The pointer-up runs past the right/bottom edge: 300/200 → clamped to 1/1.
    drag(layer, [50, 25], [300, 200]);

    expect(onRegionSelected).toHaveBeenCalledWith(
      0,
      { x: 0.25, y: 0.25, width: 0.75, height: 0.75 },
      { left: 300, top: 200 },
    );
  });

  it('draws a preview rectangle while dragging and clears it on drop', () => {
    const { layer } = renderLayer();

    expect(layer.querySelector('div')).toBeNull();

    fireEvent.pointerDown(layer, { button: 0, pointerId: 1, clientX: 50, clientY: 25 });
    fireEvent.pointerMove(layer, { pointerId: 1, clientX: 150, clientY: 75 });
    expect(layer.querySelector('div')).not.toBeNull();

    fireEvent.pointerUp(layer, { pointerId: 1, clientX: 150, clientY: 75 });
    expect(layer.querySelector('div')).toBeNull();
  });

  it('ignores non-primary buttons', () => {
    const { layer, onRegionSelected } = renderLayer();

    fireEvent.pointerDown(layer, { button: 2, pointerId: 1, clientX: 50, clientY: 25 });
    fireEvent.pointerUp(layer, { pointerId: 1, clientX: 150, clientY: 75 });

    expect(onRegionSelected).not.toHaveBeenCalled();
  });

  it('does nothing when disabled', () => {
    const { layer, onRegionSelected } = renderLayer(false);

    drag(layer, [50, 25], [150, 75]);

    expect(onRegionSelected).not.toHaveBeenCalled();
    expect(layer.setPointerCapture).not.toHaveBeenCalled();
  });

  it('ignores a move or up that never started with a pointer-down', () => {
    const { layer, onRegionSelected } = renderLayer();

    fireEvent.pointerMove(layer, { pointerId: 1, clientX: 150, clientY: 75 });
    fireEvent.pointerUp(layer, { pointerId: 1, clientX: 150, clientY: 75 });

    expect(onRegionSelected).not.toHaveBeenCalled();
  });
});
