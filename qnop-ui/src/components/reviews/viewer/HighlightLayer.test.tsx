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

import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { Anchor, AnnotationView, RenderedTextSpan } from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { HighlightLayer } from './HighlightLayer';

function wrap(children: ReactNode) {
  return <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>;
}

/** Two lines: "Hello world" (0..11) + "Second line" (12..23). */
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

const annotation = (
  id: string,
  surfaceIndex: number,
  placementStatus: PlacementStatus = PlacementStatus.Placed,
  status: AnnotationStatus = AnnotationStatus.Open,
  anchorOverride?: Anchor,
): AnnotationView => ({
  id,
  documentId: 'd1',
  authorId: 'u1',
  status,
  placementStatus,
  anchor: anchorOverride ?? {
    region: { surfaceIndex, box: { x: 0.25, y: 0.5, width: 0.4, height: 0.05 } },
    textQuote: { quote: 'the quoted passage' },
  },
  commentCount: 1,
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
});

describe('HighlightLayer', () => {
  it('draws only the annotations placed on its surface, at the stored box', () => {
    render(
      wrap(
        <HighlightLayer
          annotations={[annotation('on-surface', 0), annotation('other-surface', 1)]}
          surfaceIndex={0}
          spans={SPANS}
          activeAnnotationId={null}
          onSelect={() => {}}
        />,
      ),
    );

    const highlight = screen.getByTestId('highlight-on-surface');
    expect(highlight).toHaveStyle({ left: '25%', top: '50%', width: '40%', height: '5%' });
    expect(screen.queryByTestId('highlight-other-surface')).not.toBeInTheDocument();
  });

  it('paints a text anchor as one marker band per line', () => {
    const textAnchor: Anchor = {
      region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.5, height: 0.07 } },
      textQuote: { quote: 'world\nSecond' },
      textPosition: { start: 6, end: 18 },
    };
    render(
      wrap(
        <HighlightLayer
          annotations={[
            annotation('text-1', 0, PlacementStatus.Placed, AnnotationStatus.Open, textAnchor),
          ]}
          surfaceIndex={0}
          spans={SPANS}
          activeAnnotationId={null}
          onSelect={() => {}}
        />,
      ),
    );

    const primary = screen.getByTestId('highlight-text-1');
    const rects = primary.parentElement!.querySelectorAll(
      '[id^=annotation-highlight-], [aria-hidden=true]',
    );
    // Two lines → two marker bands: the primary (focusable) one plus one aria-hidden band.
    expect(rects).toHaveLength(2);
    // First line: "world" trimmed proportionally, expanded by the 1.3 overshoot.
    const computed = getComputedStyle(primary);
    expect(parseFloat(computed.top)).toBeCloseTo((0.1 - 0.02 * 0.15) * 100);
    expect(parseFloat(computed.height)).toBeCloseTo(0.02 * 1.3 * 100);
    // Marker bands have no border — the highlighter look.
    expect(computed.borderStyle).toBe('none');
  });

  it('selects the annotation on click and on keyboard activation', () => {
    const onSelect = vi.fn();
    render(
      wrap(
        <HighlightLayer
          annotations={[annotation('a1', 0)]}
          surfaceIndex={0}
          spans={SPANS}
          activeAnnotationId={null}
          onSelect={onSelect}
        />,
      ),
    );

    fireEvent.click(screen.getByTestId('highlight-a1'));
    fireEvent.keyDown(screen.getByTestId('highlight-a1'), { key: 'Enter' });
    expect(onSelect).toHaveBeenCalledTimes(2);
    expect(onSelect).toHaveBeenCalledWith('a1');
  });

  it('exposes the quote in the accessible name', () => {
    render(
      wrap(
        <HighlightLayer
          annotations={[annotation('a1', 0)]}
          surfaceIndex={0}
          spans={SPANS}
          activeAnnotationId={null}
          onSelect={() => {}}
        />,
      ),
    );

    expect(
      screen.getByRole('button', { name: /Annotation: the quoted passage/ }),
    ).toBeInTheDocument();
  });

  it('previews a pending region anchor as a dashed box without pointer interaction', () => {
    render(
      wrap(
        <HighlightLayer
          annotations={[]}
          surfaceIndex={0}
          spans={SPANS}
          activeAnnotationId={null}
          onSelect={() => {}}
          pendingAnchor={{
            region: { surfaceIndex: 0, box: { x: 0.1, y: 0.2, width: 0.3, height: 0.1 } },
          }}
        />,
      ),
    );

    const pending = screen.getByTestId('pending-highlight');
    expect(pending).toHaveStyle({ left: '10%', top: '20%' });
    expect(pending).toHaveStyle({ 'pointer-events': 'none' });
  });

  it('previews a pending text anchor as per-line marker bands', () => {
    render(
      wrap(
        <HighlightLayer
          annotations={[]}
          surfaceIndex={0}
          spans={SPANS}
          activeAnnotationId={null}
          onSelect={() => {}}
          pendingAnchor={{
            region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.5, height: 0.07 } },
            textQuote: { quote: 'world\nSecond' },
            textPosition: { start: 6, end: 18 },
          }}
        />,
      ),
    );

    const pending = screen.getByTestId('pending-highlight');
    // Line-wise: the preview follows the first line's box, not the union box.
    const computed = getComputedStyle(pending);
    expect(parseFloat(computed.top)).toBeCloseTo((0.1 - 0.02 * 0.15) * 100);
    expect(parseFloat(computed.height)).toBeCloseTo(0.02 * 1.3 * 100);
  });
});
