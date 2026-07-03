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

/**
 * Couples the two compare panes' vertical scrolling proportionally (the
 * versions rarely share a pixel height — inserted pages shift everything).
 * An echo guard swallows the scroll event a programmatic follow triggers on
 * the other pane, so the panes never feed back into each other.
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
    let echo: HTMLElement | null = null;
    const follow = (source: HTMLElement, target: HTMLElement) => () => {
      if (echo === source) {
        echo = null;
        return;
      }
      const sourceMax = source.scrollHeight - source.clientHeight;
      const targetMax = target.scrollHeight - target.clientHeight;
      if (sourceMax <= 0 || targetMax <= 0) return;
      const next = (source.scrollTop / sourceMax) * targetMax;
      if (Math.abs(next - target.scrollTop) < 1) return;
      echo = target;
      target.scrollTop = next;
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
