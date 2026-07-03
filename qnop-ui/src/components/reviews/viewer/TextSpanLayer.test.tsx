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

const SPANS: RenderedTextSpan[] = [
  {
    text: 'Hello world',
    startOffset: 0,
    endOffset: 11,
    box: { x: 0.1, y: 0.1, width: 0.5, height: 0.02 },
  },
  {
    text: 'Second line',
    startOffset: 12,
    endOffset: 23,
    box: { x: 0.1, y: 0.15, width: 0.4, height: 0.02 },
  },
];

function renderLayer(onTextSelected = vi.fn()) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <TextSpanLayer
        spans={SPANS}
        surfaceIndex={0}
        pageWidth={800}
        pageHeight={1035}
        enabled
        onTextSelected={onTextSelected}
      />
    </ThemeProvider>,
  );
  return onTextSelected;
}

describe('TextSpanLayer', () => {
  it('positions each span centred on its box with marker overshoot and transparent glyphs', () => {
    renderLayer();

    const span = screen.getByText('Hello world');
    expect(span).toHaveAttribute('data-span-start', '0');
    expect(span).toHaveAttribute('data-span-length', '11');
    // Fixture pitch 0.05 on 0.02-tall boxes → the marker line paints the full
    // pitch (5%), with a quarter of the extra height above the box top.
    expect(span).toHaveStyle({ left: '10%', color: 'rgba(0, 0, 0, 0)' });
    expect(parseFloat(span.style.top)).toBeCloseTo(9.25);
    expect(parseFloat(span.style.height)).toBeCloseTo(5);
  });

  it('reports a cross-span selection as canonical-text offsets', () => {
    const onTextSelected = renderLayer();

    // Select "world" (6..11) through "Second" (12..18) via a real DOM range.
    const first = screen.getByText('Hello world').firstChild!;
    const second = screen.getByText('Second line').firstChild!;
    const range = document.createRange();
    range.setStart(first, 6);
    range.setEnd(second, 6);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);

    fireEvent.pointerUp(screen.getByTestId('text-layer-0'), { clientX: 120, clientY: 240 });

    expect(onTextSelected).toHaveBeenCalledWith(
      { surfaceIndex: 0, start: 6, end: 18 },
      { left: 120, top: 240 },
    );
  });

  it('ignores a collapsed selection', () => {
    const onTextSelected = renderLayer();
    window.getSelection()?.removeAllRanges();

    fireEvent.pointerUp(screen.getByTestId('text-layer-0'));

    expect(onTextSelected).not.toHaveBeenCalled();
  });

  it('mirrors the live selection as marker bands and clears them on collapse', () => {
    renderLayer();

    const first = screen.getByText('Hello world').firstChild!;
    const range = document.createRange();
    range.setStart(first, 0);
    range.setEnd(first, 5);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);
    fireEvent(document, new Event('selectionchange'));

    expect(screen.getByTestId('live-selection-0')).toBeInTheDocument();

    selection.removeAllRanges();
    fireEvent(document, new Event('selectionchange'));

    expect(screen.queryByTestId('live-selection-0')).not.toBeInTheDocument();
  });
});
