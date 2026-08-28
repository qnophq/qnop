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

import { BREAKPOINTS } from './breakpoints';
import { expect, open, test } from './fixtures';
import { expectNoHorizontalOverflow, expectTouchTarget } from './layout';

/**
 * The surfaces of the #461 audit matrix, each as a path the signed-in admin
 * can open. The review workspace, its tasks board and version compare take the
 * smoke review's id.
 */
const SURFACES = [
  { name: 'dashboard', path: () => '/' },
  { name: 'reviews list', path: () => '/reviews' },
  { name: 'review workspace', path: (id: string) => `/reviews/${id}` },
  { name: 'review workspace, focus mode', path: (id: string) => `/reviews/${id}?view=focus` },
  { name: 'tasks board', path: (id: string) => `/reviews/${id}/tasks` },
  { name: 'version compare', path: (id: string) => `/reviews/${id}/compare` },
  { name: 'messages', path: () => '/messages' },
  { name: 'my teams', path: () => '/my-teams' },
  { name: 'profile', path: () => '/profile' },
  { name: 'audit', path: () => '/audit' },
  { name: 'admin users', path: () => '/admin/users' },
  { name: 'admin teams', path: () => '/admin/teams' },
  { name: 'admin settings', path: () => '/admin/settings' },
  { name: 'admin scheduler', path: () => '/admin/scheduler' },
  { name: 'admin mail templates', path: () => '/admin/mail-templates' },
  { name: 'admin storage consistency', path: () => '/admin/storage-consistency' },
] as const;

const HEADER_CONTROLS = ['Toggle menu', /Switch to (dark|light) mode/, /^Notifications/] as const;

for (const breakpoint of BREAKPOINTS) {
  test.describe(`at ${breakpoint.width}×${breakpoint.height}`, () => {
    test.use({ viewport: { width: breakpoint.width, height: breakpoint.height } });

    for (const surface of SURFACES) {
      test(`${surface.name} does not scroll sideways`, async ({ page, smokeReviewId }) => {
        // The viewer toolbar's trailing control group is 412 px and does not
        // wrap (issue #772): the workspace overflows `main` at 320 and 375, in
        // split view and in focus mode. Expected until fixed — Playwright then
        // fails on the unexpected pass, so the marker cannot outlive the bug.
        test.fail(
          surface.name.startsWith('review workspace') && breakpoint.width <= 375,
          'issue #772',
        );
        // KPI card rows do not wrap: the storage-consistency row overflows at
        // 320, the dashboard's at 320 and 375 (issue #773).
        test.fail(
          (surface.name === 'admin storage consistency' && breakpoint.width === 320) ||
            (surface.name === 'dashboard' && breakpoint.width <= 375),
          'issue #773',
        );
        // The review head's action row (784 px) is wider than the content beside
        // an open sidebar at 1024, on every review route (issue #774).
        test.fail(
          ['review workspace', 'tasks board', 'version compare'].some((name) =>
            surface.name.startsWith(name),
          ) && breakpoint.width === 1024,
          'issue #774',
        );
        await open(page, surface.path(smokeReviewId));
        await expectNoHorizontalOverflow(page);
      });
    }

    test('header controls are touch targets', async ({ page }) => {
      await open(page, '/');
      for (const name of HEADER_CONTROLS) {
        await expectTouchTarget(page.getByRole('banner').getByRole('button', { name }));
      }
    });
  });
}

test.describe('signed out', () => {
  for (const breakpoint of BREAKPOINTS) {
    test(`login does not scroll sideways at ${breakpoint.width}`, async ({ browser }) => {
      const context = await browser.newContext({
        viewport: { width: breakpoint.width, height: breakpoint.height },
      });
      const page = await context.newPage();
      await page.goto('/login');
      await expect(page.locator('form button[type=submit]')).toBeVisible();
      await expectNoHorizontalOverflow(page);
      await context.close();
    });
  }
});
