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

import type {
  Anchor,
  AnnotationView,
  NormalizedBox,
  RenderedTextSpan,
} from '../../../api/generated';

/**
 * Pure anchor-building logic for the viewer (ADR-0009). The client never
 * derives geometry from its own PDF rendering — every coordinate here comes
 * from the server-extracted text spans and their normalized 0..1 boxes
 * (ADR-0032), so anchors agree with what re-anchoring will later see.
 */

/** Characters of surrounding context stored with a text-quote anchor. */
export const QUOTE_CONTEXT_CHARS = 32;

/** Smallest normalized edge a drawn region may have — guards against stray clicks. */
export const MIN_REGION_EDGE = 0.004;

/**
 * How much taller a text marker paints than the extracted glyph box, centred
 * on the printed line. PDF viewers (macOS Preview, Acrobat) and Word overshoot
 * the glyphs the same way — ascenders/descenders stay covered and adjacent
 * lines merge into one continuous marker. Used by both the live selection
 * (TextSpanLayer) and persisted text highlights (HighlightLayer).
 */
export const MARKER_OVERSHOOT = 1.3;

/** A text selection expressed in canonical-text offsets of one surface. */
export interface TextSelectionOffsets {
  surfaceIndex: number;
  /** Inclusive start offset into the surface's canonical text. */
  start: number;
  /** Exclusive end offset. */
  end: number;
}

/** The canonical text of a surface: span texts joined by single newlines (API contract). */
export function surfaceText(spans: RenderedTextSpan[]): string {
  return spans.map((span) => span.text).join('\n');
}

function clampUnit(value: number): number {
  return Math.min(1, Math.max(0, value));
}

/** Clamps a box into the normalized 0..1 space of its surface. */
export function clampBox(box: NormalizedBox): NormalizedBox {
  const x = clampUnit(box.x);
  const y = clampUnit(box.y);
  return {
    x,
    y,
    width: clampUnit(Math.min(box.width, 1 - x)),
    height: clampUnit(Math.min(box.height, 1 - y)),
  };
}

/** The bounding box of the given boxes, or null for an empty list. */
export function unionBoxes(boxes: NormalizedBox[]): NormalizedBox | null {
  if (boxes.length === 0) return null;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const box of boxes) {
    minX = Math.min(minX, box.x);
    minY = Math.min(minY, box.y);
    maxX = Math.max(maxX, box.x + box.width);
    maxY = Math.max(maxY, box.y + box.height);
  }
  return clampBox({ x: minX, y: minY, width: maxX - minX, height: maxY - minY });
}

/**
 * The highlight boxes covering the canonical-text range [start, end).
 * Partially selected spans are trimmed proportionally by character count — an
 * approximation (glyph widths vary), acceptable because the authoritative
 * anchor is the text quote; the box only needs to visually cover the selection.
 */
export function boxesForRange(
  spans: RenderedTextSpan[],
  start: number,
  end: number,
): NormalizedBox[] {
  const boxes: NormalizedBox[] = [];
  for (const span of spans) {
    const overlapStart = Math.max(start, span.startOffset);
    const overlapEnd = Math.min(end, span.endOffset);
    if (overlapEnd <= overlapStart || span.text.length === 0) continue;
    const fromFraction = (overlapStart - span.startOffset) / span.text.length;
    const widthFraction = (overlapEnd - overlapStart) / span.text.length;
    boxes.push(
      clampBox({
        x: span.box.x + span.box.width * fromFraction,
        y: span.box.y,
        width: span.box.width * widthFraction,
        height: span.box.height,
      }),
    );
  }
  return boxes;
}

/**
 * Builds the multi-layer anchor (region + text-quote + text-position,
 * ADR-0009) for a text selection. Returns null when the range is empty,
 * whitespace-only, or covers no span.
 */
export function buildTextAnchor(
  surfaceIndex: number,
  spans: RenderedTextSpan[],
  start: number,
  end: number,
): Anchor | null {
  const text = surfaceText(spans);
  const from = Math.max(0, Math.min(start, end));
  const to = Math.min(text.length, Math.max(start, end));
  if (to <= from) return null;
  const quote = text.slice(from, to);
  if (quote.trim().length === 0) return null;
  const box = unionBoxes(boxesForRange(spans, from, to));
  if (!box) return null;
  const prefix = text.slice(Math.max(0, from - QUOTE_CONTEXT_CHARS), from);
  const suffix = text.slice(to, Math.min(text.length, to + QUOTE_CONTEXT_CHARS));
  return {
    region: { surfaceIndex, box },
    textQuote: {
      quote,
      ...(prefix.length > 0 && { prefix }),
      ...(suffix.length > 0 && { suffix }),
    },
    textPosition: { start: from, end: to },
  };
}

/**
 * Builds a region-only anchor from a drawn rectangle. Accepts boxes with
 * negative width/height (drag direction), clamps into 0..1 and rejects
 * degenerate rectangles below {@link MIN_REGION_EDGE}.
 */
export function buildRegionAnchor(surfaceIndex: number, box: NormalizedBox): Anchor | null {
  const x1 = clampUnit(Math.min(box.x, box.x + box.width));
  const y1 = clampUnit(Math.min(box.y, box.y + box.height));
  const x2 = clampUnit(Math.max(box.x, box.x + box.width));
  const y2 = clampUnit(Math.max(box.y, box.y + box.height));
  const rect: NormalizedBox = { x: x1, y: y1, width: x2 - x1, height: y2 - y1 };
  if (rect.width < MIN_REGION_EDGE || rect.height < MIN_REGION_EDGE) return null;
  return { region: { surfaceIndex, box: rect } };
}

/** How an anchor paints on its surface: line-wise text marker, or a plain box. */
export interface HighlightGeometry {
  kind: 'marker' | 'box';
  boxes: NormalizedBox[];
}

function expandMarkerLine(box: NormalizedBox): NormalizedBox {
  return clampBox({
    x: box.x,
    y: box.y - (box.height * (MARKER_OVERSHOOT - 1)) / 2,
    width: box.width,
    height: box.height * MARKER_OVERSHOOT,
  });
}

/**
 * The highlight geometry of an anchor on its surface: a text anchor paints as
 * per-line marker boxes (recomputed from the stored text-position offsets
 * against the surface's spans, each with {@link MARKER_OVERSHOOT}); a
 * region-only anchor — or a text anchor whose offsets no longer hit any span —
 * falls back to the stored region bounding box.
 */
export function highlightBoxesForAnchor(
  anchor: Anchor,
  spans: RenderedTextSpan[],
): HighlightGeometry {
  if (anchor.textPosition && spans.length > 0) {
    const lines = boxesForRange(spans, anchor.textPosition.start, anchor.textPosition.end);
    if (lines.length > 0) {
      return { kind: 'marker', boxes: lines.map(expandMarkerLine) };
    }
  }
  return { kind: 'box', boxes: [anchor.region.box] };
}

/**
 * Orders annotations by their resolved position on the current version:
 * surface first, then top edge, then left edge. Annotations without a resolved
 * anchor (orphaned/failed placements) sort last.
 */
export function compareAnnotationsByPosition(a: AnnotationView, b: AnnotationView): number {
  const ra = a.anchor?.region;
  const rb = b.anchor?.region;
  if (!ra && !rb) return a.createdAt.localeCompare(b.createdAt);
  if (!ra) return 1;
  if (!rb) return -1;
  return ra.surfaceIndex - rb.surfaceIndex || ra.box.y - rb.box.y || ra.box.x - rb.box.x;
}
