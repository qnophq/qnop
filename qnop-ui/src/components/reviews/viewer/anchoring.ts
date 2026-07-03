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
 * Text-marker line metrics. Word and native PDF viewers paint a highlight over
 * the FULL line box (baseline to baseline), so consecutive lines merge into
 * one continuous marker. The extracted span box is only the ascent (its bottom
 * edge is the baseline — descenders hang below it), so the marker height is
 * derived from the surface's measured line pitch, bounded to stay sane on
 * unusual layouts, and the extra height is distributed asymmetrically: most of
 * it below the box, covering the descenders.
 */
export const MARKER_MIN_FACTOR = 1.35;
export const MARKER_MAX_FACTOR = 2.6;
/** Marker height relative to the span box when the surface has no pitch info. */
export const MARKER_FALLBACK_FACTOR = 1.45;
/** Share of the extra marker height that goes above the box top. */
const MARKER_EXTRA_TOP_SHARE = 0.25;

/** A text selection expressed in canonical-text offsets of one surface. */
export interface TextSelectionOffsets {
  surfaceIndex: number;
  /** Inclusive start offset into the surface's canonical text. */
  start: number;
  /** Exclusive end offset. */
  end: number;
}

/** A viewport position (e.g. the pointer on release) for anchoring a popup. */
export interface ScreenPosition {
  left: number;
  top: number;
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

/** The length of the text without its trailing whitespace. */
function visibleLength(text: string): number {
  let end = text.length;
  while (end > 0 && /\s/.test(text[end - 1])) end--;
  return end;
}

/**
 * The x of the right edge of the span's character `index` (0-based): glyph-true
 * from the server's charAdvances (#290) when present, otherwise the uniform
 * i/len approximation across the box.
 */
export function charRightEdge(span: RenderedTextSpan, index: number): number {
  const advances = span.charAdvances;
  if (advances && advances.length === span.text.length) {
    return advances[Math.min(index, advances.length - 1)];
  }
  return span.box.x + (span.box.width * (index + 1)) / span.text.length;
}

/** The x of the left edge of the span's character `index` (0-based). */
export function charLeftEdge(span: RenderedTextSpan, index: number): number {
  return index <= 0 ? span.box.x : charRightEdge(span, index - 1);
}

/**
 * The highlight boxes covering the canonical-text range [start, end). Partial
 * lines are cut at the true glyph edges when the span carries charAdvances
 * (#290); without them the cut falls back to the proportional character-count
 * approximation. Each line is clamped to its visible text: trailing whitespace
 * (e.g. lines padded to a fixed width) never paints — matching Word/Acrobat,
 * where a highlight ends at the last printed character.
 */
export function boxesForRange(
  spans: RenderedTextSpan[],
  start: number,
  end: number,
): NormalizedBox[] {
  const boxes: NormalizedBox[] = [];
  for (const span of spans) {
    const visibleEnd = span.startOffset + visibleLength(span.text);
    const overlapStart = Math.max(start, span.startOffset);
    const overlapEnd = Math.min(end, span.endOffset, visibleEnd);
    if (overlapEnd <= overlapStart || span.text.length === 0) continue;
    const fromX = charLeftEdge(span, overlapStart - span.startOffset);
    const toX = charRightEdge(span, overlapEnd - 1 - span.startOffset);
    boxes.push(
      clampBox({
        x: fromX,
        y: span.box.y,
        width: toX - fromX,
        height: span.box.height,
      }),
    );
  }
  return boxes;
}

/**
 * The canonical-text caret offset nearest to a point on the surface (both
 * normalized 0..1). Hit-testing prefers the line whose marker band contains
 * the y (the same band the selection paints), then the nearest band; within a
 * line the caret snaps to the closest character boundary — glyph-true when
 * charAdvances exist. Null when the surface has no text.
 */
export function caretOffsetAtPoint(
  spans: RenderedTextSpan[],
  x: number,
  y: number,
  pitch: number | null,
): number | null {
  let best: { offset: number; dy: number; dx: number } | null = null;
  for (const span of spans) {
    if (span.text.length === 0) continue;
    const band = markerLineBox(span.box, pitch);
    const dy = y < band.y ? band.y - y : y > band.y + band.height ? y - (band.y + band.height) : 0;
    const right = span.box.x + span.box.width;
    let dx = 0;
    let offset: number;
    if (x <= span.box.x) {
      dx = span.box.x - x;
      offset = span.startOffset;
    } else if (x >= right) {
      dx = x - right;
      offset = span.startOffset + visibleLength(span.text);
    } else {
      // Nearest character boundary: boundary i sits at the left edge of char i.
      offset = span.startOffset + span.text.length;
      let bestDist = Math.abs(x - right);
      for (let i = 0; i < span.text.length; i++) {
        const boundary = charLeftEdge(span, i);
        const dist = Math.abs(x - boundary);
        if (dist < bestDist) {
          bestDist = dist;
          offset = span.startOffset + i;
        }
      }
    }
    if (!best || dy < best.dy || (dy === best.dy && dx < best.dx)) {
      best = { offset, dy, dx };
    }
  }
  return best ? best.offset : null;
}

/**
 * The word range (canonical-text offsets) around a caret offset — the
 * double-click selection. When the caret sits on whitespace (e.g. at a word
 * end) the word to its left counts. Null on empty text or plain whitespace.
 */
export function wordRangeAt(
  spans: RenderedTextSpan[],
  offset: number,
): { start: number; end: number } | null {
  const text = surfaceText(spans);
  if (text.length === 0) return null;
  let at = Math.min(Math.max(offset, 0), text.length - 1);
  if (/\s/.test(text[at]) && at > 0 && /\S/.test(text[at - 1])) at--;
  if (/\s/.test(text[at])) return null;
  let start = at;
  let end = at + 1;
  while (start > 0 && /\S/.test(text[start - 1])) start--;
  while (end < text.length && /\S/.test(text[end])) end++;
  return { start, end };
}

/**
 * Builds the multi-layer anchor (region + text-quote + text-position,
 * ADR-0009) for a text selection. Whitespace at either end of the range is
 * trimmed away first (Word/Acrobat semantics: a mark starts and ends on a
 * printed character — and the stored quote stays clean for re-anchoring).
 * Returns null when the range is empty, whitespace-only, or covers no span.
 */
export function buildTextAnchor(
  surfaceIndex: number,
  spans: RenderedTextSpan[],
  start: number,
  end: number,
): Anchor | null {
  const text = surfaceText(spans);
  let from = Math.max(0, Math.min(start, end));
  let to = Math.min(text.length, Math.max(start, end));
  while (from < to && /\s/.test(text[from])) from++;
  while (to > from && /\s/.test(text[to - 1])) to--;
  if (to <= from) return null;
  const quote = text.slice(from, to);
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

/**
 * The surface's typical line pitch: the median gap between distinct line tops.
 * The median is robust against paragraph gaps and headings. Null when the
 * surface has fewer than two text lines.
 */
export function surfaceLinePitch(spans: RenderedTextSpan[]): number | null {
  const tops = [...new Set(spans.map((span) => span.box.y))].sort((a, b) => a - b);
  const deltas: number[] = [];
  for (let i = 1; i < tops.length; i++) {
    const delta = tops[i] - tops[i - 1];
    if (delta > 1e-6) deltas.push(delta);
  }
  if (deltas.length === 0) return null;
  deltas.sort((a, b) => a - b);
  return deltas[Math.floor(deltas.length / 2)];
}

/**
 * The marker band for one text line: the span box grown to the line pitch
 * (Word-style full-line highlight), with most of the extra height below the
 * box — the box bottom is the baseline, so that is where descenders hang.
 */
export function markerLineBox(box: NormalizedBox, pitch: number | null): NormalizedBox {
  const factor =
    pitch && box.height > 0
      ? Math.min(Math.max(pitch / box.height, MARKER_MIN_FACTOR), MARKER_MAX_FACTOR)
      : MARKER_FALLBACK_FACTOR;
  const height = box.height * factor;
  const extra = height - box.height;
  return clampBox({
    x: box.x,
    y: box.y - extra * MARKER_EXTRA_TOP_SHARE,
    width: box.width,
    height,
  });
}

/**
 * The highlight geometry of an anchor on its surface: a text anchor paints as
 * per-line marker boxes (recomputed from the stored text-position offsets
 * against the surface's spans, each grown to the line pitch via
 * {@link markerLineBox}); a region-only anchor — or a text anchor whose
 * offsets no longer hit any span — falls back to the stored region bounding
 * box.
 */
export function highlightBoxesForAnchor(
  anchor: Anchor,
  spans: RenderedTextSpan[],
): HighlightGeometry {
  if (anchor.textPosition && spans.length > 0) {
    const lines = boxesForRange(spans, anchor.textPosition.start, anchor.textPosition.end);
    if (lines.length > 0) {
      const pitch = surfaceLinePitch(spans);
      return { kind: 'marker', boxes: lines.map((line) => markerLineBox(line, pitch)) };
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
