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

import { useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';

const KEYBOARD_STEP = 24;

interface ResizeHandleOptions {
  /** localStorage key for the persisted width — one preference per surface. */
  storageKey: string;
  defaultWidth: number;
  minWidth: number;
  /** Upper bound, read live so window resizes are honoured. */
  maxWidth?: () => number;
}

export interface ResizeHandleState {
  width: number;
  resizing: boolean;
  handleProps: {
    onPointerDown: (event: PointerEvent<HTMLDivElement>) => void;
    onPointerMove: (event: PointerEvent<HTMLDivElement>) => void;
    onPointerUp: (event: PointerEvent<HTMLDivElement>) => void;
    onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => void;
  };
}

/** Keeps a slice of the underlying page visible even on generous drags. */
function defaultMaxWidth(minWidth: number): number {
  return Math.max(minWidth, Math.min(900, window.innerWidth - 240));
}

/**
 * The width of a right-docked, user-resizable surface (issue #403): a leading
 * grab handle drags it (pointer capture), arrow keys nudge it on the focused
 * separator, bounds are clamped, and the choice persists per `storageKey`.
 * Shared by the review drawers and the writing stage's context rail.
 */
export function useResizeHandle({
  storageKey,
  defaultWidth,
  minWidth,
  maxWidth,
}: ResizeHandleOptions): ResizeHandleState {
  const clamp = (width: number) =>
    Math.min(Math.max(width, minWidth), maxWidth ? maxWidth() : defaultMaxWidth(minWidth));

  const [width, setWidth] = useState<number>(() => {
    try {
      const value = Number(localStorage.getItem(storageKey));
      return Number.isFinite(value) && value >= minWidth ? clamp(value) : defaultWidth;
    } catch {
      return defaultWidth;
    }
  });
  const [resizing, setResizing] = useState(false);
  // The live value for the pointer-up persist — state updates lag the drag.
  const widthRef = useRef(width);

  const persist = (value: number) => {
    try {
      localStorage.setItem(storageKey, String(value));
    } catch {
      // best-effort persistence
    }
  };

  const applyWidth = (next: number) => {
    const clamped = clamp(next);
    widthRef.current = clamped;
    setWidth(clamped);
    return clamped;
  };

  return {
    width,
    resizing,
    handleProps: {
      onPointerDown: (event) => {
        event.preventDefault();
        event.currentTarget.setPointerCapture?.(event.pointerId);
        setResizing(true);
      },
      onPointerMove: (event) => {
        if (!event.currentTarget.hasPointerCapture?.(event.pointerId)) return;
        // Anchored right: the surface spans from the pointer to the viewport edge.
        applyWidth(window.innerWidth - event.clientX);
      },
      onPointerUp: (event) => {
        event.currentTarget.releasePointerCapture?.(event.pointerId);
        setResizing(false);
        persist(widthRef.current);
      },
      onKeyDown: (event) => {
        // The handle sits on the LEADING edge: left grows the surface, right shrinks it.
        if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
        event.preventDefault();
        persist(
          applyWidth(
            widthRef.current + (event.key === 'ArrowLeft' ? KEYBOARD_STEP : -KEYBOARD_STEP),
          ),
        );
      },
    },
  };
}
