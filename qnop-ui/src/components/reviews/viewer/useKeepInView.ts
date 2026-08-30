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

import { useEffect, useRef } from 'react';
import type { RefObject } from 'react';

/**
 * Keeps a moving element inside the scrolled viewport (issue #782). Both
 * keyboard paths in the viewer — the text caret (#460) and the region
 * rectangle (#771) — render a draft element that arrow keys move; on a page
 * taller than the viewport that draft can walk out of view while the page
 * container stays put, and a sighted keyboard user steers blind.
 *
 * Pass a value that changes whenever the draft moves (a position key). Each
 * change schedules one `scrollIntoView({ block: 'nearest' })` on the next
 * animation frame; a held-down key produces many changes per frame, and only
 * the last one runs, so the scroller is never thrashed. `nearest` is a no-op
 * while the element is already visible. Null means "nothing to follow".
 */
export function useKeepInView<T extends HTMLElement>(
  positionKey: string | null,
): RefObject<T | null> {
  const ref = useRef<T | null>(null);
  const frameRef = useRef<number | null>(null);

  useEffect(() => {
    if (positionKey === null) return;
    if (frameRef.current !== null) cancelAnimationFrame(frameRef.current);
    frameRef.current = requestAnimationFrame(() => {
      frameRef.current = null;
      ref.current?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
    });
    return () => {
      if (frameRef.current !== null) {
        cancelAnimationFrame(frameRef.current);
        frameRef.current = null;
      }
    };
  }, [positionKey]);

  return ref;
}
