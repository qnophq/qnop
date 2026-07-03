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

import { describe, expect, it } from 'vitest';
import type { AnnotationView, RenderedTextSpan } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import {
  MARKER_OVERSHOOT,
  QUOTE_CONTEXT_CHARS,
  boxesForRange,
  buildRegionAnchor,
  buildTextAnchor,
  clampBox,
  compareAnnotationsByPosition,
  highlightBoxesForAnchor,
  surfaceText,
  unionBoxes,
} from './anchoring';

/** Two lines: "Hello world" (0..11) + '\n' (11) + "Second line" (12..23). */
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

describe('surfaceText', () => {
  it('joins spans with single newlines, matching the server offsets', () => {
    const text = surfaceText(SPANS);
    expect(text).toBe('Hello world\nSecond line');
    expect(text.slice(SPANS[1].startOffset, SPANS[1].endOffset)).toBe('Second line');
  });

  it('returns an empty string for a surface without a text layer', () => {
    expect(surfaceText([])).toBe('');
  });
});

describe('clampBox', () => {
  it('clamps coordinates and sizes into the unit square', () => {
    expect(clampBox({ x: -0.2, y: 0.9, width: 2, height: 0.3 })).toEqual({
      x: 0,
      y: 0.9,
      width: 1,
      height: expect.closeTo(0.1),
    });
  });
});

describe('unionBoxes', () => {
  it('returns null for an empty list', () => {
    expect(unionBoxes([])).toBeNull();
  });

  it('returns the bounding box across boxes', () => {
    const union = unionBoxes([
      { x: 0.1, y: 0.1, width: 0.2, height: 0.02 },
      { x: 0.3, y: 0.2, width: 0.3, height: 0.02 },
    ]);
    expect(union).toEqual({ x: 0.1, y: 0.1, width: 0.5, height: 0.12 });
  });
});

describe('boxesForRange', () => {
  it('trims a partially selected span proportionally by characters', () => {
    // "world" = offsets 6..11 of an 11-char span 0.5 wide starting at x=0.1.
    const boxes = boxesForRange(SPANS, 6, 11);
    expect(boxes).toHaveLength(1);
    expect(boxes[0].x).toBeCloseTo(0.1 + 0.5 * (6 / 11));
    expect(boxes[0].width).toBeCloseTo(0.5 * (5 / 11));
    expect(boxes[0].y).toBe(0.1);
  });

  it('covers every span the range touches', () => {
    const boxes = boxesForRange(SPANS, 6, 18);
    expect(boxes).toHaveLength(2);
  });

  it('skips spans outside the range', () => {
    expect(boxesForRange(SPANS, 0, 5)).toHaveLength(1);
    expect(boxesForRange(SPANS, 11, 12)).toHaveLength(0);
  });
});

describe('buildTextAnchor', () => {
  it('builds all three layers for a selection', () => {
    const anchor = buildTextAnchor(0, SPANS, 6, 18);
    expect(anchor).not.toBeNull();
    expect(anchor?.textQuote?.quote).toBe('world\nSecond');
    expect(anchor?.textQuote?.prefix).toBe('Hello ');
    expect(anchor?.textQuote?.suffix).toBe(' line');
    expect(anchor?.textPosition).toEqual({ start: 6, end: 18 });
    expect(anchor?.region.surfaceIndex).toBe(0);
    // Region spans both lines vertically.
    expect(anchor?.region.box.y).toBeCloseTo(0.1);
    expect(anchor?.region.box.height).toBeCloseTo(0.07);
  });

  it('swaps reversed offsets and clamps out-of-range offsets', () => {
    const anchor = buildTextAnchor(0, SPANS, 999, 12);
    expect(anchor?.textQuote?.quote).toBe('Second line');
    expect(anchor?.textPosition).toEqual({ start: 12, end: 23 });
  });

  it('omits prefix at the very start of the surface', () => {
    const anchor = buildTextAnchor(0, SPANS, 0, 5);
    expect(anchor?.textQuote?.quote).toBe('Hello');
    expect(anchor?.textQuote?.prefix).toBeUndefined();
    expect(anchor?.textQuote?.suffix).toBe(` world\nSecond line`.slice(0, QUOTE_CONTEXT_CHARS));
  });

  it('rejects empty and whitespace-only selections', () => {
    expect(buildTextAnchor(0, SPANS, 5, 5)).toBeNull();
    expect(buildTextAnchor(0, SPANS, 11, 12)).toBeNull();
    expect(buildTextAnchor(0, [], 0, 5)).toBeNull();
  });
});

describe('buildRegionAnchor', () => {
  it('normalizes a drag in any direction', () => {
    const anchor = buildRegionAnchor(2, { x: 0.5, y: 0.6, width: -0.2, height: -0.1 });
    expect(anchor?.region).toEqual({
      surfaceIndex: 2,
      box: { x: 0.3, y: 0.5, width: expect.closeTo(0.2), height: expect.closeTo(0.1) },
    });
    expect(anchor?.textQuote).toBeUndefined();
  });

  it('clamps to the surface', () => {
    const anchor = buildRegionAnchor(0, { x: 0.9, y: 0.9, width: 0.5, height: 0.5 });
    expect(anchor?.region.box).toEqual({
      x: 0.9,
      y: 0.9,
      width: expect.closeTo(0.1),
      height: expect.closeTo(0.1),
    });
  });

  it('rejects degenerate rectangles from stray clicks', () => {
    expect(buildRegionAnchor(0, { x: 0.5, y: 0.5, width: 0.001, height: 0.2 })).toBeNull();
    expect(buildRegionAnchor(0, { x: 0.5, y: 0.5, width: 0.2, height: 0 })).toBeNull();
  });
});

describe('highlightBoxesForAnchor', () => {
  it('paints a text anchor as one overshot marker box per line', () => {
    const anchor = buildTextAnchor(0, SPANS, 6, 18)!;
    const geometry = highlightBoxesForAnchor(anchor, SPANS);

    expect(geometry.kind).toBe('marker');
    expect(geometry.boxes).toHaveLength(2);
    // First line box vertically expanded by the overshoot, centred on the glyphs.
    expect(geometry.boxes[0].y).toBeCloseTo(0.1 - (0.02 * (MARKER_OVERSHOOT - 1)) / 2);
    expect(geometry.boxes[0].height).toBeCloseTo(0.02 * MARKER_OVERSHOOT);
  });

  it('falls back to the stored region box for region-only anchors', () => {
    const anchor = buildRegionAnchor(0, { x: 0.2, y: 0.3, width: 0.1, height: 0.1 })!;
    const geometry = highlightBoxesForAnchor(anchor, SPANS);

    expect(geometry.kind).toBe('box');
    expect(geometry.boxes).toEqual([anchor.region.box]);
  });

  it('falls back to the region box when the surface has no spans or the offsets miss', () => {
    const anchor = buildTextAnchor(0, SPANS, 6, 18)!;
    expect(highlightBoxesForAnchor(anchor, []).kind).toBe('box');

    const stale = { ...anchor, textPosition: { start: 500, end: 600 } };
    expect(highlightBoxesForAnchor(stale, SPANS).kind).toBe('box');
  });
});

describe('compareAnnotationsByPosition', () => {
  const annotation = (
    id: string,
    region: { surfaceIndex: number; y: number; x?: number } | null,
    createdAt = '2026-07-01T10:00:00Z',
  ): AnnotationView => ({
    id,
    documentId: 'd1',
    authorId: 'u1',
    status: AnnotationStatus.Open,
    commentCount: 0,
    createdAt,
    updatedAt: createdAt,
    ...(region && {
      anchor: {
        region: {
          surfaceIndex: region.surfaceIndex,
          box: { x: region.x ?? 0.1, y: region.y, width: 0.2, height: 0.02 },
        },
      },
    }),
  });

  it('orders by surface, then top edge, and puts unplaced annotations last', () => {
    const sorted = [
      annotation('unplaced', null),
      annotation('page2', { surfaceIndex: 1, y: 0.1 }),
      annotation('page1-low', { surfaceIndex: 0, y: 0.8 }),
      annotation('page1-top', { surfaceIndex: 0, y: 0.2 }),
    ].sort(compareAnnotationsByPosition);
    expect(sorted.map((a) => a.id)).toEqual(['page1-top', 'page1-low', 'page2', 'unplaced']);
  });

  it('orders unplaced annotations by creation time', () => {
    const sorted = [
      annotation('newer', null, '2026-07-02T10:00:00Z'),
      annotation('older', null, '2026-07-01T10:00:00Z'),
    ].sort(compareAnnotationsByPosition);
    expect(sorted.map((a) => a.id)).toEqual(['older', 'newer']);
  });
});
