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
import type { KeyboardEvent, PointerEvent } from 'react';
import Box from '@mui/material/Box';
import { useTheme } from '@mui/material/styles';

export const PANEL_MIN_WIDTH = 360;
export const PANEL_MAX_WIDTH = 720;
const KEYBOARD_STEP = 16;

interface PanelResizerProps {
  /** Current width of the panel to the RIGHT of the divider. */
  width: number;
  defaultWidth: number;
  onWidthChange: (width: number) => void;
}

function clamp(width: number): number {
  return Math.min(PANEL_MAX_WIDTH, Math.max(PANEL_MIN_WIDTH, width));
}

/**
 * The draggable divider between the document pane and the annotation panel:
 * dragging left widens the panel (down to its current default as the
 * minimum), double-click resets, and arrow keys resize from the keyboard —
 * a real `separator` with value semantics for assistive tech. The visible
 * hairline thickens and tints on hover/drag; the hit area stays comfortably
 * wide.
 */
export function PanelResizer({ width, defaultWidth, onWidthChange }: PanelResizerProps) {
  const theme = useTheme();
  // Drag state lives in a ref so pointermove handlers never read a stale
  // closure (moves can arrive before React re-renders); the state mirror only
  // drives the visual dragging style.
  const draggingRef = useRef(false);
  const [dragging, setDragging] = useState(false);
  const start = useRef({ x: 0, width });

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    event.preventDefault();
    start.current = { x: event.clientX, width };
    draggingRef.current = true;
    setDragging(true);
    try {
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // Synthetic pointer events (tests) have no active pointer to capture.
    }
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current) return;
    // Dragging left grows the right-hand panel.
    onWidthChange(clamp(start.current.width + (start.current.x - event.clientX)));
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const next =
      event.key === 'ArrowLeft'
        ? width + KEYBOARD_STEP
        : event.key === 'ArrowRight'
          ? width - KEYBOARD_STEP
          : event.key === 'Home'
            ? PANEL_MAX_WIDTH
            : event.key === 'End'
              ? PANEL_MIN_WIDTH
              : null;
    if (next === null) return;
    event.preventDefault();
    onWidthChange(clamp(next));
  };

  return (
    <Box
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize annotations panel"
      aria-valuemin={PANEL_MIN_WIDTH}
      aria-valuemax={PANEL_MAX_WIDTH}
      aria-valuenow={width}
      tabIndex={0}
      data-dragging={dragging}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={() => {
        draggingRef.current = false;
        setDragging(false);
      }}
      onPointerCancel={() => {
        draggingRef.current = false;
        setDragging(false);
      }}
      onDoubleClick={() => onWidthChange(defaultWidth)}
      onKeyDown={handleKeyDown}
      sx={{
        width: 16,
        flexShrink: 0,
        alignSelf: 'stretch',
        display: { xs: 'none', md: 'flex' },
        alignItems: 'stretch',
        justifyContent: 'center',
        cursor: 'col-resize',
        touchAction: 'none',
        userSelect: 'none',
        borderRadius: 1,
        '&::before': {
          content: '""',
          width: '2px',
          borderRadius: 1,
          bgcolor: theme.palette.divider,
          transition: 'background-color 120ms ease, width 120ms ease',
        },
        '&:hover::before, &[data-dragging="true"]::before': {
          width: '3px',
          bgcolor: theme.qnop.brand.blue,
        },
        '&:focus-visible': { outline: 'none', boxShadow: theme.qnop.focusRing },
        '@media (prefers-reduced-motion: reduce)': { '&::before': { transition: 'none' } },
      }}
    />
  );
}
