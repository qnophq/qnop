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

import { useRef } from 'react';
import type { PointerEvent } from 'react';
import Box from '@mui/material/Box';
import type { RenderedTextSpan } from '../../../api/generated';
import type { ScreenPosition, TextSelectionOffsets } from './anchoring';
import { markerLineBox, surfaceLinePitch } from './anchoring';
import { SELECTION_MARKER_BG } from './markerColors';

/**
 * The font the invisible glyphs are measured and rendered with. Monospace is
 * deliberate: with the run stretched to the span box (scaleX), every character
 * then sits at i/len of the box — the same uniform-grid model boxesForRange
 * uses for drawing. A proportional layer font would only match the box in
 * total width, so dragging over the printed glyphs would hit neighbouring
 * characters in the invisible run (selection offsets shifted by a few chars).
 */
const LAYER_FONT_FAMILY = 'monospace';

interface TextSpanLayerProps {
  spans: RenderedTextSpan[];
  surfaceIndex: number;
  /** Current display width of the page in CSS pixels — scales glyph runs to their boxes. */
  pageWidth: number;
  /** Current display height of the page in CSS pixels — sizes the invisible glyphs. */
  pageHeight: number;
  enabled: boolean;
  onTextSelected: (selection: TextSelectionOffsets, at: ScreenPosition) => void;
}

/** Finds the enclosing span element carrying canonical-text offsets. */
function spanElementFor(node: Node | null): HTMLElement | null {
  if (!node) return null;
  const element = node instanceof HTMLElement ? node : node.parentElement;
  return element?.closest<HTMLElement>('[data-span-start]') ?? null;
}

// One shared 2D context for glyph-run measurement (null in jsdom → scale 1).
let sharedMeasureContext: CanvasRenderingContext2D | null | undefined;

/** The width of a glyph run in the layer font, or null when measuring is unavailable. */
function measureGlyphRun(text: string, fontSize: number): number | null {
  if (sharedMeasureContext === undefined) {
    sharedMeasureContext = document.createElement('canvas').getContext('2d');
  }
  if (!sharedMeasureContext) return null;
  sharedMeasureContext.font = `${fontSize}px ${LAYER_FONT_FAMILY}`;
  const width = sharedMeasureContext.measureText(text).width;
  return width > 0 ? width : null;
}

/**
 * An invisible, selectable text layer built from the server-extracted spans
 * (ADR-0032 — the client is not the authority on where text is; it only makes
 * the server's text selectable). Like pdf.js's own text layer, each span is
 * absolutely positioned at its normalized box and its glyph run is stretched
 * horizontally (scaleX from a canvas measurement) to cover the box, so the
 * browser's selection rectangles trace the printed text. The glyphs themselves
 * stay transparent — also while selected — and the selection paints as a
 * translucent marker; the offsets attached to each span are the canonical-text
 * offsets the anchor model needs (ADR-0009).
 */
export function TextSpanLayer({
  spans,
  surfaceIndex,
  pageWidth,
  pageHeight,
  enabled,
  onTextSelected,
}: TextSpanLayerProps) {
  const rootRef = useRef<HTMLDivElement>(null);

  const handlePointerUp = (event: PointerEvent<HTMLDivElement>) => {
    const selection = window.getSelection();
    const root = rootRef.current;
    if (!selection || selection.rangeCount === 0 || selection.isCollapsed || !root) return;
    const range = selection.getRangeAt(0);
    const startElement = spanElementFor(range.startContainer);
    const endElement = spanElementFor(range.endContainer);
    // Both ends must sit on this surface's spans; cross-surface selections are ignored.
    if (
      !startElement ||
      !endElement ||
      !root.contains(startElement) ||
      !root.contains(endElement)
    ) {
      return;
    }
    const start =
      Number(startElement.dataset.spanStart) +
      (range.startContainer.nodeType === Node.TEXT_NODE ? range.startOffset : 0);
    const end =
      Number(endElement.dataset.spanStart) +
      (range.endContainer.nodeType === Node.TEXT_NODE
        ? range.endOffset
        : Number(endElement.dataset.spanLength));
    if (end <= start) return;
    selection.removeAllRanges();
    onTextSelected({ surfaceIndex, start, end }, { left: event.clientX, top: event.clientY });
  };

  const pitch = surfaceLinePitch(spans);

  return (
    <Box
      ref={rootRef}
      data-testid={`text-layer-${surfaceIndex}`}
      onPointerUp={handlePointerUp}
      sx={{
        position: 'absolute',
        inset: 0,
        overflow: 'hidden',
        cursor: enabled ? 'text' : 'default',
        pointerEvents: enabled ? 'auto' : 'none',
        userSelect: enabled ? 'text' : 'none',
        // The transparent-marker feel: the selection paints a translucent
        // highlight while the layer's approximated glyphs stay invisible —
        // without the color override the browser would repaint the selected
        // glyphs (in the layer's font, not the page's) as visible text.
        '& span::selection': {
          backgroundColor: SELECTION_MARKER_BG,
          color: 'transparent',
        },
      }}
    >
      {spans.map((span) => {
        const fontSize = Math.max(span.box.height * pageHeight * 0.85, 6);
        const measured = span.text.length > 0 ? measureGlyphRun(span.text, fontSize) : null;
        const scaleX = measured ? (span.box.width * pageWidth) / measured : 1;
        // The selection paints the line box: grown to the surface's line pitch
        // and shifted like the persisted markers, so selecting and the created
        // highlight cover the printed line identically (Word-style full line).
        const line = markerLineBox(span.box, pitch);
        const lineHeightPx = Math.max(line.height * pageHeight, 9);
        return (
          <span
            key={span.startOffset}
            data-span-start={span.startOffset}
            data-span-length={span.text.length}
            style={{
              position: 'absolute',
              left: `${span.box.x * 100}%`,
              top: `${line.y * 100}%`,
              height: `${line.height * 100}%`,
              color: 'transparent',
              whiteSpace: 'pre',
              fontFamily: LAYER_FONT_FAMILY,
              fontSize: `${fontSize}px`,
              lineHeight: `${lineHeightPx}px`,
              // Stretch the glyph run to the server box so the selection
              // rectangles trace the printed text (same trick as pdf.js).
              transform: `scaleX(${scaleX})`,
              transformOrigin: '0 0',
            }}
          >
            {span.text}
          </span>
        );
      })}
    </Box>
  );
}
