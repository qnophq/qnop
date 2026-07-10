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

import { useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import Drawer from '@mui/material/Drawer';
import { alpha, useTheme } from '@mui/material/styles';

/** Never narrower than the panel needs to breathe, never the whole viewport. */
const MIN_WIDTH = 380;
const DEFAULT_WIDTH = 520;
const KEYBOARD_STEP = 24;
const WIDTH_KEY = 'qnop-focus-drawer-width';

/** Keeps a slice of the document visible even on generous drags. */
function maxWidth(): number {
  return Math.max(MIN_WIDTH, Math.min(900, window.innerWidth - 240));
}

function clampWidth(width: number): number {
  return Math.min(Math.max(width, MIN_WIDTH), maxWidth());
}

function storedWidth(): number {
  try {
    const value = Number(localStorage.getItem(WIDTH_KEY));
    return Number.isFinite(value) && value >= MIN_WIDTH ? clampWidth(value) : DEFAULT_WIDTH;
  } catch {
    return DEFAULT_WIDTH;
  }
}

function persistWidth(width: number) {
  try {
    localStorage.setItem(WIDTH_KEY, String(width));
  } catch {
    // best-effort persistence
  }
}

interface FocusDrawerProps {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
}

/**
 * Focus mode's temporary home of the annotation list (issue #291): the full
 * panel — filters, sections, the orphaned group — slides in on demand and
 * disappears again, keeping the document at full width the rest of the time.
 * The width is a personal working preference (issue #403): a grab handle on
 * the leading edge resizes it (pointer drag, or arrow keys on the focused
 * separator) and the choice persists in localStorage.
 */
export function FocusDrawer({ open, onClose, children }: FocusDrawerProps) {
  const theme = useTheme();
  const [width, setWidth] = useState<number>(storedWidth);
  const [resizing, setResizing] = useState(false);
  // The live value for the pointer-up persist — state updates lag the drag.
  const widthRef = useRef(width);

  const applyWidth = (next: number) => {
    const clamped = clampWidth(next);
    widthRef.current = clamped;
    setWidth(clamped);
    return clamped;
  };

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    setResizing(true);
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!event.currentTarget.hasPointerCapture?.(event.pointerId)) return;
    // Anchored right: the drawer spans from the pointer to the viewport edge.
    applyWidth(window.innerWidth - event.clientX);
  };

  const handlePointerUp = (event: PointerEvent<HTMLDivElement>) => {
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    setResizing(false);
    persistWidth(widthRef.current);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    // The handle sits on the LEADING edge: left grows the drawer, right shrinks it.
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    persistWidth(applyWidth(width + (event.key === 'ArrowLeft' ? KEYBOARD_STEP : -KEYBOARD_STEP)));
  };

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      slotProps={{ paper: { sx: { width: { xs: '100%', sm: width }, overflow: 'visible' } } }}
    >
      <Box
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize the annotation drawer"
        aria-valuemin={MIN_WIDTH}
        aria-valuenow={Math.round(width)}
        tabIndex={0}
        data-testid="focus-drawer-resize-handle"
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onKeyDown={handleKeyDown}
        sx={{
          position: 'absolute',
          left: -5,
          top: 0,
          bottom: 0,
          width: 10,
          zIndex: 1,
          display: { xs: 'none', sm: 'flex' },
          alignItems: 'center',
          justifyContent: 'center',
          cursor: 'col-resize',
          touchAction: 'none',
          '&:focus-visible': { outline: 'none', boxShadow: theme.qnop.focusRing },
          // The visible grip: a quiet hairline that wakes on hover/drag.
          '&::after': {
            content: '""',
            width: '3px',
            height: 48,
            borderRadius: 2,
            bgcolor: resizing ? theme.qnop.brand.blue : theme.palette.divider,
            transition: 'background-color 120ms ease, height 120ms ease',
          },
          '&:hover::after': {
            bgcolor: resizing ? theme.qnop.brand.blue : alpha(theme.qnop.brand.blue, 0.6),
            height: 72,
          },
          '@media (prefers-reduced-motion: reduce)': { '&::after': { transition: 'none' } },
        }}
      />
      <Box sx={{ p: 2, overflowY: 'auto', height: '100%' }}>{children}</Box>
    </Drawer>
  );
}
