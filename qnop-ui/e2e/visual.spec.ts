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

import { BREAKPOINTS, VISUAL_WIDTHS } from './breakpoints';
import { expect, fixedTime, open, settle, test } from './fixtures';

/**
 * Screenshot baselines for the surfaces whose layout the #461 audit cares
 * about most, at the four widths the issue names. Both themes share one
 * layout — the theme changes colours, not boxes — so the light baseline is
 * the one kept; a dark set would double the images for no extra signal.
 *
 * Update with `pnpm test:e2e:update` and review the changed images in the
 * diff like any other change.
 */
const SURFACES = [
  { name: 'dashboard', path: () => '/' },
  { name: 'reviews', path: () => '/reviews' },
  { name: 'workspace', path: (id: string) => `/reviews/${id}` },
  { name: 'tasks', path: (id: string) => `/reviews/${id}/tasks` },
] as const;

const visual = BREAKPOINTS.filter((breakpoint) => VISUAL_WIDTHS.includes(breakpoint.name));

for (const breakpoint of visual) {
  test.describe(`at ${breakpoint.width}`, () => {
    test.use({ viewport: { width: breakpoint.width, height: breakpoint.height } });

    for (const surface of SURFACES) {
      test(`${surface.name} looks as before`, async ({ page, smokeReviewId }) => {
        await open(page, surface.path(smokeReviewId));
        await expect(page).toHaveScreenshot(`${surface.name}-${breakpoint.name}.png`, {
          // Avatars and the relative times are seeded and the clock is
          // fixed, so nothing dynamic remains to mask.
          fullPage: false,
        });
      });
    }

    test('login looks as before', async ({ browser }) => {
      const context = await browser.newContext({
        viewport: { width: breakpoint.width, height: breakpoint.height },
      });
      const page = await context.newPage();
      await page.clock.setFixedTime(fixedTime());
      await page.goto('/login');
      await expect(page.locator('form button[type=submit]')).toBeVisible();
      await settle(page);
      await expect(page).toHaveScreenshot(`login-${breakpoint.name}.png`);
      await context.close();
    });
  });
}
