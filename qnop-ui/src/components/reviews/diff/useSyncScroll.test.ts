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
import { renderHook } from '@testing-library/react';
import { useSyncScroll } from './useSyncScroll';

function scrollable(scrollHeight: number, clientHeight: number): HTMLDivElement {
  const element = document.createElement('div');
  Object.defineProperty(element, 'scrollHeight', { value: scrollHeight, configurable: true });
  Object.defineProperty(element, 'clientHeight', { value: clientHeight, configurable: true });
  return element;
}

function setup(enabled = true) {
  const left = scrollable(2000, 500); // 1500 scrollable
  const right = scrollable(3500, 500); // 3000 scrollable
  renderHook(({ on }) => useSyncScroll({ current: left }, { current: right }, on), {
    initialProps: { on: enabled },
  });
  return { left, right };
}

describe('useSyncScroll', () => {
  it('follows a scroll proportionally on the other pane', () => {
    const { left, right } = setup();

    left.scrollTop = 750; // half of the left range
    left.dispatchEvent(new Event('scroll'));

    expect(right.scrollTop).toBe(1500); // half of the right range
  });

  it('does not bounce the programmatic follow back to the source', () => {
    const { left, right } = setup();

    left.scrollTop = 150; // 10% of the left range
    left.dispatchEvent(new Event('scroll'));
    expect(right.scrollTop).toBe(300);
    // The browser fires a scroll event for the programmatic follow — the echo
    // guard must swallow it instead of re-syncing the left pane.
    right.dispatchEvent(new Event('scroll'));

    expect(left.scrollTop).toBe(150);
  });

  it('stays inert when disabled', () => {
    const { left, right } = setup(false);

    left.scrollTop = 750;
    left.dispatchEvent(new Event('scroll'));

    expect(right.scrollTop).toBe(0);
  });
});
