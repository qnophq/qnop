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

import { expect, type Locator, type Page } from '@playwright/test';

/** WCAG 2.5.8 / issue #461: the smallest box a finger can hit reliably. */
export const TOUCH_TARGET_PX = 44;

interface Overflow {
  readonly element: string;
  readonly scrollWidth: number;
  readonly clientWidth: number;
}

/**
 * Asserts that nothing on the page scrolls sideways.
 *
 * Checks the document AND the shell's content container: below `md` that
 * container has `overflow: auto` (issue #723), so a surface wider than the
 * viewport scrolls inside it and the document itself stays clean. Checking
 * only `documentElement` therefore passes while the page silently scrolls.
 */
export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const overflowing = await page.evaluate((): Overflow[] => {
    const candidates: Array<[string, Element | null]> = [
      ['documentElement', document.documentElement],
      ['body', document.body],
      ['main', document.querySelector('main')],
    ];
    return candidates
      .filter((entry): entry is [string, Element] => entry[1] !== null)
      .map(([element, node]) => ({
        element,
        scrollWidth: node.scrollWidth,
        clientWidth: node.clientWidth,
      }))
      .filter((entry) => entry.scrollWidth > entry.clientWidth);
  });
  expect(overflowing, 'elements that scroll horizontally').toEqual([]);
}

/** Asserts that a control's hit box is at least 44 px on both axes. */
export async function expectTouchTarget(control: Locator): Promise<void> {
  const box = await control.evaluate((node) => {
    // The header controls grow their hit area with a ::before overlay (#724),
    // so the box to measure is the overlay's, not the button's own.
    const own = node.getBoundingClientRect();
    const before = getComputedStyle(node, '::before');
    if (before.content === 'none' || before.position !== 'absolute') {
      return { width: own.width, height: own.height };
    }
    const grown = (value: string, base: number) => {
      const px = Number.parseFloat(value);
      return Number.isNaN(px) ? base : Math.max(px, base);
    };
    return { width: grown(before.width, own.width), height: grown(before.height, own.height) };
  });
  const label = (await control.getAttribute('aria-label')) ?? (await control.textContent());
  expect(box.width, `${label}: width`).toBeGreaterThanOrEqual(TOUCH_TARGET_PX);
  expect(box.height, `${label}: height`).toBeGreaterThanOrEqual(TOUCH_TARGET_PX);
}
