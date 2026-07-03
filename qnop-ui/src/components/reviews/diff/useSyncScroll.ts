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

import { useEffect } from 'react';

/** The proportional position on the target range, or null when an axis cannot scroll. */
function mapped(value: number, sourceMax: number, targetMax: number): number | null {
  if (sourceMax <= 0 || targetMax <= 0) return null;
  return (value / sourceMax) * targetMax;
}

/**
 * Couples the two compare panes' scrolling proportionally on BOTH axes (the
 * versions rarely share a pixel height, and zoomed pages overflow
 * horizontally). A mute flag plus a sub-pixel delta check swallow the scroll
 * events a programmatic follow triggers on the other pane — writing two
 * scroll properties can fire more than one event, so a one-shot echo marker
 * would bounce back.
 *
 * Takes the ELEMENTS (from callback refs held in state), not ref objects: the
 * panes mount after the data guards, so the listeners must (re)attach when
 * the elements appear — a plain ref would leave the effect bound to null.
 */
export function useSyncScroll(
  left: HTMLDivElement | null,
  right: HTMLDivElement | null,
  enabled: boolean,
) {
  useEffect(() => {
    if (!enabled || !left || !right) return undefined;
    let muted = false;
    const follow = (source: HTMLElement, target: HTMLElement) => () => {
      if (muted) return;
      const nextTop = mapped(
        source.scrollTop,
        source.scrollHeight - source.clientHeight,
        target.scrollHeight - target.clientHeight,
      );
      const nextLeft = mapped(
        source.scrollLeft,
        source.scrollWidth - source.clientWidth,
        target.scrollWidth - target.clientWidth,
      );
      const moveTop = nextTop !== null && Math.abs(nextTop - target.scrollTop) >= 1;
      const moveLeft = nextLeft !== null && Math.abs(nextLeft - target.scrollLeft) >= 1;
      if (!moveTop && !moveLeft) return;
      muted = true;
      if (moveTop) target.scrollTop = nextTop;
      if (moveLeft) target.scrollLeft = nextLeft;
      requestAnimationFrame(() => {
        muted = false;
      });
    };
    const onLeft = follow(left, right);
    const onRight = follow(right, left);
    left.addEventListener('scroll', onLeft, { passive: true });
    right.addEventListener('scroll', onRight, { passive: true });
    return () => {
      left.removeEventListener('scroll', onLeft);
      right.removeEventListener('scroll', onRight);
    };
  }, [left, right, enabled]);
}
