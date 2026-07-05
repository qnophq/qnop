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

/** Document : panel = 2 : 1 by default (issue #403). */
export const DEFAULT_PANEL_FRACTION = 1 / 3;
export const PANEL_MIN_FRACTION = 0.2;
export const PANEL_MAX_FRACTION = 0.5;
const KEYBOARD_STEP = 0.02;
/** The divider's own width — subtracted before the panes split the rest. */
export const RESIZER_WIDTH = 16;

interface PanelResizerProps {
  /** The panel's share of the split (0..1), panel to the RIGHT of the divider. */
  fraction: number;
  onFractionChange: (fraction: number) => void;
}

function clamp(fraction: number): number {
  return Math.min(PANEL_MAX_FRACTION, Math.max(PANEL_MIN_FRACTION, fraction));
}

/**
 * The draggable divider between the document pane and the annotation panel.
 * The split is a FRACTION of the container — the default reads document :
 * panel = 2 : 1 at any window size (issue #403) — dragging left widens the
 * panel, double-click restores the default, and arrow keys resize from the
 * keyboard: a real `separator` with percent value semantics for assistive
 * tech. The visible hairline thickens and tints on hover/drag; the hit area
 * stays comfortably wide.
 */
export function PanelResizer({ fraction, onFractionChange }: PanelResizerProps) {
  const theme = useTheme();
  // Drag state lives in a ref so pointermove handlers never read a stale
  // closure (moves can arrive before React re-renders); the state mirror only
  // drives the visual dragging style.
  const draggingRef = useRef(false);
  const [dragging, setDragging] = useState(false);
  // The split container's box, captured once per drag — the divider sits
  // directly inside it, so the parent IS the container.
  const containerBox = useRef({ right: 0, width: 1 });

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    event.preventDefault();
    const parent = event.currentTarget.parentElement;
    if (parent) {
      const rect = parent.getBoundingClientRect();
      containerBox.current = { right: rect.right, width: Math.max(1, rect.width) };
    }
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
    // The panel spans from the pointer (plus half the divider) to the right edge.
    const { right, width } = containerBox.current;
    onFractionChange(clamp((right - event.clientX - RESIZER_WIDTH / 2) / width));
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const next =
      event.key === 'ArrowLeft'
        ? fraction + KEYBOARD_STEP
        : event.key === 'ArrowRight'
          ? fraction - KEYBOARD_STEP
          : event.key === 'Home'
            ? PANEL_MAX_FRACTION
            : event.key === 'End'
              ? PANEL_MIN_FRACTION
              : null;
    if (next === null) return;
    event.preventDefault();
    onFractionChange(clamp(next));
  };

  return (
    <Box
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize annotations panel"
      aria-valuemin={Math.round(PANEL_MIN_FRACTION * 100)}
      aria-valuemax={Math.round(PANEL_MAX_FRACTION * 100)}
      aria-valuenow={Math.round(fraction * 100)}
      aria-valuetext={`${Math.round(fraction * 100)}% of the workspace`}
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
      onDoubleClick={() => onFractionChange(DEFAULT_PANEL_FRACTION)}
      onKeyDown={handleKeyDown}
      sx={{
        width: RESIZER_WIDTH,
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
