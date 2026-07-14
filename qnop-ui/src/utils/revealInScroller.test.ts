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

import { describe, expect, it } from 'vitest';
import { targetScrollTop } from './revealInScroller';

/** A scroller viewport of 400px showing a 1000px list, currently at scrollTop. */
function scroller(scrollTop: number) {
  return {
    scrollTop,
    scrollHeight: 1000,
    clientHeight: 400,
    getBoundingClientRect: () => ({ top: 100, bottom: 500 }),
  } as unknown as HTMLElement;
}

/** A row of the given height whose top sits at `top` in viewport coordinates. */
function row(top: number, height = 60) {
  return {
    getBoundingClientRect: () => ({ top, bottom: top + height, height }),
  } as unknown as HTMLElement;
}

describe('targetScrollTop (issue #491)', () => {
  it("'nearest' leaves a fully visible row alone — in-list clicks never jump", () => {
    expect(targetScrollTop(scroller(200), row(150), 'nearest')).toBeNull();
  });

  it("'nearest' scrolls a row above the fold to the top edge", () => {
    // Row top is 80px above the viewport top (100): elTop = 80-100+200 = 180.
    expect(targetScrollTop(scroller(200), row(80), 'nearest')).toBe(180);
  });

  it("'nearest' scrolls a row below the fold just into view", () => {
    // elTop = 550-100+200 = 650; align bottom: 650 - (400 - 60) = 310.
    expect(targetScrollTop(scroller(200), row(550), 'nearest')).toBe(310);
  });

  it("'start' aligns the row head to the top with a small gap", () => {
    // elTop = 350-100+200 = 450; minus the 8px gap.
    expect(targetScrollTop(scroller(200), row(350), 'start')).toBe(442);
  });

  it("'start' clamps to the scrollable range", () => {
    // elTop = 1000-100+500 = 1400 → far beyond max (1000-400=600).
    expect(targetScrollTop(scroller(500), row(1000), 'start')).toBe(600);
  });

  it("'start' reports null when already aligned", () => {
    // elTop = 108-100+200 = 208; minus gap = 200 — exactly the current position.
    expect(targetScrollTop(scroller(200), row(108), 'start')).toBeNull();
  });
});
