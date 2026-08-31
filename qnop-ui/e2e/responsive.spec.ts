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
        // First catch of this net (issue #809): three surfaces regressed since
        // the Aug-29 audit — the reviews list overflows the page at every width
        // (a fixed ~973 px element escapes the table's scroll container), and two
        // admin tables flipped at single widths. Expected until fixed — Playwright
        // fails on the unexpected pass, so the marker cannot outlive the bug.
        test.fail(surface.name === 'reviews list', 'issue #809');
        test.fail(
          surface.name === 'admin storage consistency' &&
            [375, 768, 1440].includes(breakpoint.width),
          'issue #809',
        );
        test.fail(surface.name === 'admin scheduler' && breakpoint.width === 320, 'issue #809');
        test.fail(surface.name === 'review workspace' && breakpoint.width === 1440, 'issue #809');
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
