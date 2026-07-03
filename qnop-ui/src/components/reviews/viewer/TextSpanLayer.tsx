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

import { useRef, useState } from 'react';
import type { MouseEvent, PointerEvent } from 'react';
import Box from '@mui/material/Box';
import type { RenderedTextSpan } from '../../../api/generated';
import type { ScreenPosition, TextSelectionOffsets } from './anchoring';
import {
  boxesForRange,
  caretOffsetAtPoint,
  markerLineBox,
  surfaceLinePitch,
  wordRangeAt,
} from './anchoring';
import { SELECTION_MARKER_BG } from './markerColors';

interface TextSpanLayerProps {
  spans: RenderedTextSpan[];
  surfaceIndex: number;
  enabled: boolean;
  onTextSelected: (selection: TextSelectionOffsets, at: ScreenPosition) => void;
}

/**
 * The text-selection layer, driven entirely by the server-extracted spans
 * (ADR-0032 — the client is not the authority on where text is). Selection is
 * pointer-based (#290): the caret maps through the spans' glyph-true
 * charAdvances (uniform fallback for older representations), so dragging over
 * the printed glyphs selects exactly the characters under the pointer — no
 * invisible DOM text whose approximated grid could drift from the print. The
 * layer draws its own multiply-blended marker bands while dragging, identical
 * to the pending preview and persisted highlights; double-click selects the
 * word under the pointer. The emitted offsets are the canonical-text offsets
 * the anchor model needs (ADR-0009).
 */
export function TextSpanLayer({
  spans,
  surfaceIndex,
  enabled,
  onTextSelected,
}: TextSpanLayerProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ anchor: number } | null>(null);
  const [liveRange, setLiveRange] = useState<{ start: number; end: number } | null>(null);

  const pitch = surfaceLinePitch(spans);

  const caretAt = (event: PointerEvent | MouseEvent): number | null => {
    const root = rootRef.current;
    if (!root) return null;
    const rect = root.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return null;
    const x = (event.clientX - rect.left) / rect.width;
    const y = (event.clientY - rect.top) / rect.height;
    return caretOffsetAtPoint(spans, x, y, pitch);
  };

  const rangeTo = (focus: number): { start: number; end: number } | null => {
    const drag = dragRef.current;
    if (!drag) return null;
    const start = Math.min(drag.anchor, focus);
    const end = Math.max(drag.anchor, focus);
    return end > start ? { start, end } : null;
  };

  const endDrag = () => {
    dragRef.current = null;
    setLiveRange(null);
  };

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (!enabled || event.button !== 0) return;
    const offset = caretAt(event);
    if (offset === null) return;
    dragRef.current = { anchor: offset };
    setLiveRange(null);
    try {
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // Synthetic pointer events (tests) have no active pointer to capture.
    }
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!dragRef.current) return;
    const offset = caretAt(event);
    if (offset === null) return;
    setLiveRange(rangeTo(offset));
  };

  const handlePointerUp = (event: PointerEvent<HTMLDivElement>) => {
    if (!dragRef.current) return;
    const offset = caretAt(event);
    const range = offset === null ? liveRange : rangeTo(offset);
    endDrag();
    if (range) {
      onTextSelected({ surfaceIndex, ...range }, { left: event.clientX, top: event.clientY });
    }
  };

  const handleDoubleClick = (event: MouseEvent<HTMLDivElement>) => {
    if (!enabled) return;
    const offset = caretAt(event);
    if (offset === null) return;
    const range = wordRangeAt(spans, offset);
    if (range) {
      onTextSelected({ surfaceIndex, ...range }, { left: event.clientX, top: event.clientY });
    }
  };

  // Guarded by `enabled` so a stale range never paints on a disabled layer.
  const liveBoxes =
    enabled && liveRange
      ? boxesForRange(spans, liveRange.start, liveRange.end).map((box) => markerLineBox(box, pitch))
      : [];

  return (
    <Box
      ref={rootRef}
      data-testid={`text-layer-${surfaceIndex}`}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={endDrag}
      onDoubleClick={handleDoubleClick}
      sx={{
        position: 'absolute',
        inset: 0,
        overflow: 'hidden',
        cursor: enabled ? 'text' : 'default',
        pointerEvents: enabled ? 'auto' : 'none',
        userSelect: 'none',
        touchAction: 'none',
      }}
    >
      {liveBoxes.map((box, index) => (
        <div
          key={index}
          data-testid={index === 0 ? `live-selection-${surfaceIndex}` : undefined}
          style={{
            position: 'absolute',
            left: `${box.x * 100}%`,
            top: `${box.y * 100}%`,
            width: `${box.width * 100}%`,
            height: `${box.height * 100}%`,
            backgroundColor: SELECTION_MARKER_BG,
            mixBlendMode: 'multiply',
            borderRadius: '1px',
            pointerEvents: 'none',
          }}
        />
      ))}
    </Box>
  );
}
