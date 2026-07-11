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

/** Never narrower than a thread needs to breathe, never the whole viewport. */
const DEFAULT_MIN_WIDTH = 380;
const KEYBOARD_STEP = 24;

/** Keeps a slice of the underlying page visible even on generous drags. */
function maxWidth(minWidth: number): number {
  return Math.max(minWidth, Math.min(900, window.innerWidth - 240));
}

function clampWidth(width: number, minWidth: number): number {
  return Math.min(Math.max(width, minWidth), maxWidth(minWidth));
}

function storedWidth(storageKey: string, minWidth: number, defaultWidth: number): number {
  try {
    const value = Number(localStorage.getItem(storageKey));
    return Number.isFinite(value) && value >= minWidth ? clampWidth(value, minWidth) : defaultWidth;
  } catch {
    return defaultWidth;
  }
}

function persistWidth(storageKey: string, width: number) {
  try {
    localStorage.setItem(storageKey, String(width));
  } catch {
    // best-effort persistence
  }
}

interface ResizableDrawerProps {
  open: boolean;
  onClose: () => void;
  /** localStorage key for the persisted width — one preference per surface. */
  storageKey: string;
  defaultWidth: number;
  minWidth?: number;
  /** Accessible name of the resize separator. */
  handleAriaLabel: string;
  handleTestId?: string;
  drawerTestId?: string;
  children: ReactNode;
}

/**
 * A right-anchored drawer whose width is a personal working preference
 * (issue #403): a grab handle on the leading edge resizes it (pointer drag,
 * or arrow keys on the focused separator) and the choice persists per
 * `storageKey`. One mechanism for every review drawer — focus-mode panel and
 * task details resize and remember identically.
 */
export function ResizableDrawer({
  open,
  onClose,
  storageKey,
  defaultWidth,
  minWidth = DEFAULT_MIN_WIDTH,
  handleAriaLabel,
  handleTestId,
  drawerTestId,
  children,
}: ResizableDrawerProps) {
  const theme = useTheme();
  const [width, setWidth] = useState<number>(() => storedWidth(storageKey, minWidth, defaultWidth));
  const [resizing, setResizing] = useState(false);
  // The live value for the pointer-up persist — state updates lag the drag.
  const widthRef = useRef(width);

  const applyWidth = (next: number) => {
    const clamped = clampWidth(next, minWidth);
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
    persistWidth(storageKey, widthRef.current);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    // The handle sits on the LEADING edge: left grows the drawer, right shrinks it.
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    // widthRef, not state: key-repeat bursts outpace re-renders.
    persistWidth(
      storageKey,
      applyWidth(widthRef.current + (event.key === 'ArrowLeft' ? KEYBOARD_STEP : -KEYBOARD_STEP)),
    );
  };

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      slotProps={{ paper: { sx: { width: { xs: '100%', sm: width }, overflow: 'visible' } } }}
      data-testid={drawerTestId}
    >
      <Box
        role="separator"
        aria-orientation="vertical"
        aria-label={handleAriaLabel}
        aria-valuemin={minWidth}
        aria-valuenow={Math.round(width)}
        tabIndex={0}
        data-testid={handleTestId}
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
      {children}
    </Drawer>
  );
}
