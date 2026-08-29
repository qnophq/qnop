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

describe('RegionSelectLayer keyboard path (issue #771)', () => {
  it('is focusable with a described keyboard hint while enabled', () => {
    const { layer } = renderLayer();
    expect(layer).toHaveAttribute('tabindex', '0');
    expect(layer).toHaveAccessibleDescription(/first arrow key press places/);
  });

  it('leaves the tab order when disabled', () => {
    const { layer } = renderLayer(false);
    expect(layer).toHaveAttribute('tabindex', '-1');
  });

  it('places the rectangle on the first arrow press and moves it on the next', () => {
    const { layer } = renderLayer();

    fireEvent.keyDown(layer, { key: 'ArrowRight' });
    const draft = screen.getByTestId('region-draft-0');
    // The initial box: centered fifth of the page.
    expect(draft.style.left).toBe('40%');
    expect(draft.style.top).toBe('40%');

    fireEvent.keyDown(layer, { key: 'ArrowRight' });
    expect(draft.style.left).toBe('42%');

    fireEvent.keyDown(layer, { key: 'ArrowDown', shiftKey: true });
    expect(draft.style.top).toBe('50%');
  });

  it('grows and shrinks the rectangle with Alt + arrows', () => {
    const { layer } = renderLayer();

    fireEvent.keyDown(layer, { key: 'ArrowRight' });
    fireEvent.keyDown(layer, { key: 'ArrowRight', altKey: true });
    const draft = screen.getByTestId('region-draft-0');
    expect(draft.style.width).toBe('22%');

    fireEvent.keyDown(layer, { key: 'ArrowUp', altKey: true, shiftKey: true });
    expect(draft.style.height).toBe('10%');
  });

  it('clamps growth at the page edge and shrinking at the minimum size', () => {
    const { layer } = renderLayer();

    fireEvent.keyDown(layer, { key: 'ArrowRight' });
    // Grow far past the right edge: width caps at 1 - x = 0.6.
    for (let i = 0; i < 8; i += 1) {
      fireEvent.keyDown(layer, { key: 'ArrowRight', altKey: true, shiftKey: true });
    }
    const draft = screen.getByTestId('region-draft-0');
    expect(draft.style.width).toBe('60%');

    // Shrink far past zero: height floors at the 2% minimum.
    for (let i = 0; i < 8; i += 1) {
      fireEvent.keyDown(layer, { key: 'ArrowUp', altKey: true, shiftKey: true });
    }
    expect(draft.style.height).toBe('2%');
  });

  it('keeps the rectangle on the page when moving against an edge', () => {
    const { layer } = renderLayer();

    fireEvent.keyDown(layer, { key: 'ArrowLeft' });
    for (let i = 0; i < 6; i += 1) {
      fireEvent.keyDown(layer, { key: 'ArrowLeft', shiftKey: true });
    }
    const draft = screen.getByTestId('region-draft-0');
    expect(draft.style.left).toBe('0%');
    expect(draft.style.width).toBe('20%');
  });

  it('commits the rectangle with Enter through the pointer path, at its bottom-right corner', () => {
    const { layer, onRegionSelected } = renderLayer();

    fireEvent.keyDown(layer, { key: 'ArrowRight' });
    fireEvent.keyDown(layer, { key: 'Enter' });

    // { x: 0.4, y: 0.4, width: 0.2, height: 0.2 } against the 200×100 rect.
    expect(onRegionSelected).toHaveBeenCalledWith(
      0,
      { x: 0.4, y: 0.4, width: 0.2, height: 0.2 },
      { left: 120, top: 60 },
    );
    expect(screen.queryByTestId('region-draft-0')).toBeNull();
  });

  it('clears the rectangle on Escape and on blur without committing', () => {
    const { layer, onRegionSelected } = renderLayer();

    fireEvent.keyDown(layer, { key: 'ArrowRight' });
    fireEvent.keyDown(layer, { key: 'Escape' });
    expect(screen.queryByTestId('region-draft-0')).toBeNull();

    fireEvent.keyDown(layer, { key: 'ArrowRight' });
    fireEvent.blur(layer);
    expect(screen.queryByTestId('region-draft-0')).toBeNull();

    expect(onRegionSelected).not.toHaveBeenCalled();
  });

  it('does nothing on Enter or Escape before a rectangle exists', () => {
    const { layer, onRegionSelected } = renderLayer();

    fireEvent.keyDown(layer, { key: 'Enter' });
    fireEvent.keyDown(layer, { key: 'Escape' });

    expect(onRegionSelected).not.toHaveBeenCalled();
    expect(screen.queryByTestId('region-draft-0')).toBeNull();
  });

  it('ignores keys when disabled', () => {
    const { layer, onRegionSelected } = renderLayer(false);

    fireEvent.keyDown(layer, { key: 'ArrowRight' });
    fireEvent.keyDown(layer, { key: 'Enter' });

    expect(onRegionSelected).not.toHaveBeenCalled();
    expect(screen.queryByTestId('region-draft-0')).toBeNull();
  });
});
