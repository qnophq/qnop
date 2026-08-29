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

import { useId, useState } from 'react';
import type { KeyboardEvent, PointerEvent } from 'react';
import Box from '@mui/material/Box';
import { useTheme } from '@mui/material/styles';
import type { NormalizedBox } from '../../../api/generated';
import type { ScreenPosition } from './anchoring';
import { MARKER_YELLOW_BORDER, SELECTION_MARKER_BG } from './markerColors';
import { visuallyHidden } from '../../../theme/visuallyHidden';

/** How the keyboard path is announced to assistive tech (issue #771). */
const KEYBOARD_HINT =
  'The first arrow key press places a selection rectangle in the middle of the page. Arrow keys then move it, Shift with an arrow key moves in larger steps, Alt with an arrow key grows or shrinks it, Enter annotates the region, Escape clears it.';

/** One arrow press moves or resizes by this fraction of the page. */
const MOVE_STEP = 0.02;
/** The Shift-accelerated step. */
const LARGE_STEP = 0.1;
/** The rectangle never shrinks below this, so it stays visible and hittable. */
const MIN_SIZE = 0.02;
/** Where the first arrow press places the rectangle: centered, clearly visible. */
const INITIAL_KEYBOARD_BOX: NormalizedBox = { x: 0.4, y: 0.4, width: 0.2, height: 0.2 };

interface RegionSelectLayerProps {
  surfaceIndex: number;
  enabled: boolean;
  onRegionSelected: (surfaceIndex: number, box: NormalizedBox, at: ScreenPosition) => void;
}

interface DraftRect {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

interface ArrowDelta {
  dx: number;
  dy: number;
}

function clampUnit(value: number): number {
  return Math.min(1, Math.max(0, value));
}

/** Kills the floating-point dust a chain of step additions accumulates. */
function round4(value: number): number {
  return Math.round(value * 1e4) / 1e4;
}

function arrowDelta(key: string): ArrowDelta | null {
  switch (key) {
    case 'ArrowLeft':
      return { dx: -1, dy: 0 };
    case 'ArrowRight':
      return { dx: 1, dy: 0 };
    case 'ArrowUp':
      return { dx: 0, dy: -1 };
    case 'ArrowDown':
      return { dx: 0, dy: 1 };
    default:
      return null;
  }
}

/** The rectangle shifted by one step, kept fully on the page. */
function moved(box: NormalizedBox, delta: ArrowDelta, step: number): NormalizedBox {
  return {
    ...box,
    x: round4(Math.min(1 - box.width, Math.max(0, box.x + delta.dx * step))),
    y: round4(Math.min(1 - box.height, Math.max(0, box.y + delta.dy * step))),
  };
}

/** The rectangle grown (right/down) or shrunk (left/up) by one step. */
function resized(box: NormalizedBox, delta: ArrowDelta, step: number): NormalizedBox {
  return {
    ...box,
    width: round4(Math.min(1 - box.x, Math.max(MIN_SIZE, box.width + delta.dx * step))),
    height: round4(Math.min(1 - box.y, Math.max(MIN_SIZE, box.height + delta.dy * step))),
  };
}

/**
 * Rubber-band region selection: drag a rectangle anywhere on the surface —
 * the universal anchor layer that works with or without a text layer
 * (ADR-0009). Coordinates are normalized against the page box, so the drawn
 * region is zoom- and DPI-independent (ADR-0032).
 *
 * Keyboard equivalent (issue #771, WCAG 2.1.1): while region mode is on the
 * layer is focusable; the first arrow press places a rectangle, arrows move
 * it (Shift for larger steps), Alt+arrows grow or shrink it, Enter commits
 * through the same `onRegionSelected` path as a pointer release, Escape
 * clears. The draft paints with the pointer preview's styling.
 */
export function RegionSelectLayer({
  surfaceIndex,
  enabled,
  onRegionSelected,
}: RegionSelectLayerProps) {
  const [draft, setDraft] = useState<DraftRect | null>(null);
  const [keyboardBox, setKeyboardBox] = useState<NormalizedBox | null>(null);
  const hintId = useId();
  const theme = useTheme();

  // `currentTarget` is the surface Box the handlers are bound to — always the
  // live element during the event, so it needs no ref and no non-null assertion.
  const toNormalized = (event: PointerEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    return {
      x: clampUnit((event.clientX - rect.left) / rect.width),
      y: clampUnit((event.clientY - rect.top) / rect.height),
    };
  };

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (!enabled || event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    setKeyboardBox(null);
    const point = toNormalized(event);
    setDraft({ x1: point.x, y1: point.y, x2: point.x, y2: point.y });
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!draft) return;
    const point = toNormalized(event);
    setDraft({ ...draft, x2: point.x, y2: point.y });
  };

  const handlePointerUp = (event: PointerEvent<HTMLDivElement>) => {
    if (!draft) return;
    setDraft(null);
    onRegionSelected(
      surfaceIndex,
      { x: draft.x1, y: draft.y1, width: draft.x2 - draft.x1, height: draft.y2 - draft.y1 },
      { left: event.clientX, top: event.clientY },
    );
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!enabled) return;
    if (event.key === 'Escape') {
      if (!keyboardBox) return;
      event.preventDefault();
      setKeyboardBox(null);
      return;
    }
    if (event.key === 'Enter') {
      if (!keyboardBox) return;
      event.preventDefault();
      // The commit position stands in for the pointer-release point: the
      // rectangle's bottom-right corner in viewport coordinates.
      const rect = event.currentTarget.getBoundingClientRect();
      setKeyboardBox(null);
      onRegionSelected(surfaceIndex, keyboardBox, {
        left: rect.left + round4(keyboardBox.x + keyboardBox.width) * rect.width,
        top: rect.top + round4(keyboardBox.y + keyboardBox.height) * rect.height,
      });
      return;
    }
    const delta = arrowDelta(event.key);
    if (delta === null) return;
    event.preventDefault();
    if (!keyboardBox) {
      // The first arrow press only places the rectangle; moving starts with
      // the next press, so the initial position is predictable.
      setKeyboardBox(INITIAL_KEYBOARD_BOX);
      return;
    }
    const step = event.shiftKey ? LARGE_STEP : MOVE_STEP;
    setKeyboardBox(
      event.altKey ? resized(keyboardBox, delta, step) : moved(keyboardBox, delta, step),
    );
  };

  const pointerPreview: NormalizedBox | null = draft && {
    x: Math.min(draft.x1, draft.x2),
    y: Math.min(draft.y1, draft.y2),
    width: Math.abs(draft.x2 - draft.x1),
    height: Math.abs(draft.y2 - draft.y1),
  };
  const preview = pointerPreview ?? keyboardBox;

  return (
    <Box
      data-testid={`region-layer-${surfaceIndex}`}
      // Names the otherwise-generic selection surface for assistive tech, the
      // same way the text layer does (issue #341).
      role="group"
      aria-label={`Region selection, page ${surfaceIndex + 1}`}
      aria-describedby={enabled ? hintId : undefined}
      tabIndex={enabled ? 0 : -1}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onKeyDown={handleKeyDown}
      onBlur={() => setKeyboardBox(null)}
      sx={{
        position: 'absolute',
        inset: 0,
        cursor: 'crosshair',
        pointerEvents: enabled ? 'auto' : 'none',
        touchAction: 'none',
        '&:focus-visible': { outline: 'none', boxShadow: `inset ${theme.qnop.focusRing}` },
      }}
    >
      {enabled && (
        <Box component="span" id={hintId} sx={visuallyHidden}>
          {KEYBOARD_HINT}
        </Box>
      )}
      {preview && (
        <div
          data-testid={`region-draft-${surfaceIndex}`}
          style={{
            position: 'absolute',
            left: `${preview.x * 100}%`,
            top: `${preview.y * 100}%`,
            width: `${preview.width * 100}%`,
            height: `${preview.height * 100}%`,
            border: `2px dashed ${MARKER_YELLOW_BORDER}`,
            backgroundColor: SELECTION_MARKER_BG,
            mixBlendMode: 'multiply',
            pointerEvents: 'none',
          }}
        />
      )}
    </Box>
  );
}
