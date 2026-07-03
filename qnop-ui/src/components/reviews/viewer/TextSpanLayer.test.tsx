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
import type { RenderedTextSpan } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { TextSpanLayer } from './TextSpanLayer';

// "Hello world" with glyph-true edges: "Hello " narrow (0.02 each), "world"
// wide (0.076 each) — deliberately far from the uniform grid (0.0454 each), so
// any test passing below proves the advances are honoured.
const ADVANCES = [0.12, 0.14, 0.16, 0.18, 0.2, 0.22, 0.296, 0.372, 0.448, 0.524, 0.6];

const SPANS: RenderedTextSpan[] = [
  {
    text: 'Hello world',
    startOffset: 0,
    endOffset: 11,
    box: { x: 0.1, y: 0.1, width: 0.5, height: 0.02 },
    charAdvances: ADVANCES,
  },
  {
    // No charAdvances — exercises the uniform fallback (0.4/11 per char).
    text: 'Second line',
    startOffset: 12,
    endOffset: 23,
    box: { x: 0.1, y: 0.15, width: 0.4, height: 0.02 },
  },
];

/** The layer maps pointer pixels through its bounding rect: pin it to 1000×1000. */
function layerAt(): HTMLElement {
  const layer = screen.getByTestId('text-layer-0');
  Object.defineProperty(layer, 'getBoundingClientRect', {
    value: () => ({
      left: 0,
      top: 0,
      width: 1000,
      height: 1000,
      right: 1000,
      bottom: 1000,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    }),
  });
  return layer;
}

function renderLayer({ enabled = true } = {}) {
  const onTextSelected = vi.fn();
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <TextSpanLayer
        spans={SPANS}
        surfaceIndex={0}
        enabled={enabled}
        onTextSelected={onTextSelected}
      />
    </ThemeProvider>,
  );
  return onTextSelected;
}

describe('TextSpanLayer', () => {
  it('selects glyph-true via charAdvances — never the uniform grid', () => {
    const onTextSelected = renderLayer();
    const layer = layerAt();

    // Down just right of the boundary before "world" (advance edge 0.22);
    // the uniform grid would place this pixel between characters 2 and 3.
    fireEvent.pointerDown(layer, { button: 0, clientX: 225, clientY: 110 });
    fireEvent.pointerMove(layer, { clientX: 600, clientY: 110 });
    fireEvent.pointerUp(layer, { clientX: 600, clientY: 110 });

    expect(onTextSelected).toHaveBeenCalledWith(
      { surfaceIndex: 0, start: 6, end: 11 },
      { left: 600, top: 110 },
    );
  });

  it('falls back to the uniform grid for spans without advances', () => {
    const onTextSelected = renderLayer();
    const layer = layerAt();

    // Span 2: uniform char width 0.4/11 ≈ 0.0364 → boundary 6 at x ≈ 0.318.
    fireEvent.pointerDown(layer, { button: 0, clientX: 100, clientY: 160 });
    fireEvent.pointerUp(layer, { clientX: 318, clientY: 160 });

    expect(onTextSelected).toHaveBeenCalledWith(
      { surfaceIndex: 0, start: 12, end: 18 },
      { left: 318, top: 160 },
    );
  });

  it('spans lines: dragging from the first into the second selects across', () => {
    const onTextSelected = renderLayer();
    const layer = layerAt();

    fireEvent.pointerDown(layer, { button: 0, clientX: 225, clientY: 110 });
    fireEvent.pointerUp(layer, { clientX: 318, clientY: 160 });

    expect(onTextSelected).toHaveBeenCalledWith(
      { surfaceIndex: 0, start: 6, end: 18 },
      { left: 318, top: 160 },
    );
  });

  it('mirrors the drag as live marker bands and clears them on release', () => {
    renderLayer();
    const layer = layerAt();

    fireEvent.pointerDown(layer, { button: 0, clientX: 225, clientY: 110 });
    fireEvent.pointerMove(layer, { clientX: 600, clientY: 110 });
    expect(screen.getByTestId('live-selection-0')).toBeInTheDocument();

    fireEvent.pointerUp(layer, { clientX: 600, clientY: 110 });
    expect(screen.queryByTestId('live-selection-0')).not.toBeInTheDocument();
  });

  it('ignores a click without a drag', () => {
    const onTextSelected = renderLayer();
    const layer = layerAt();

    fireEvent.pointerDown(layer, { button: 0, clientX: 225, clientY: 110 });
    fireEvent.pointerUp(layer, { clientX: 225, clientY: 110 });

    expect(onTextSelected).not.toHaveBeenCalled();
  });

  it('selects the word under the pointer on double click', () => {
    const onTextSelected = renderLayer();
    const layer = layerAt();

    // x = 0.4 sits inside "world" (0.22..0.6) on the first line.
    fireEvent.doubleClick(layer, { clientX: 400, clientY: 110 });

    expect(onTextSelected).toHaveBeenCalledWith(
      { surfaceIndex: 0, start: 6, end: 11 },
      { left: 400, top: 110 },
    );
  });

  it('does nothing while disabled', () => {
    const onTextSelected = renderLayer({ enabled: false });
    const layer = layerAt();

    fireEvent.pointerDown(layer, { button: 0, clientX: 225, clientY: 110 });
    fireEvent.pointerUp(layer, { clientX: 600, clientY: 110 });
    fireEvent.doubleClick(layer, { clientX: 400, clientY: 110 });

    expect(onTextSelected).not.toHaveBeenCalled();
  });
});
