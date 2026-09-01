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

import {
  test as base,
  expect as baseExpect,
  type APIRequestContext,
  type BrowserContext,
  type Page,
} from '@playwright/test';

/** The seeded admin from testdata/db/seed.sql (SeededIntegrationTest.SEED_PASSWORD). */
export const SEED_ADMIN = { username: 'admin', password: 'Test-Pass-1234!' } as const;

/** The dev server the run drives; the API is proxied through it (playwright.config.ts). */
export const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';

/**
 * The review the smoke script uploads (scripts/smoke-test.sh, "Smoke Review"):
 * a real PDF that went through extraction, moved to In review and carries one
 * annotation with a reply. Every workspace test reads it, none writes to it.
 */
export const SMOKE_REVIEW_TITLE = 'Smoke Review';

/** Set by global.setup.ts: the moment the whole run agrees on, see {@link fixedTime}. */
export const FIXED_TIME_ENV = 'E2E_FIXED_TIME';

/** One hour after the smoke review was created (see global.setup.ts). */
export function fixedTime(): Date {
  const value = process.env[FIXED_TIME_ENV];
  if (!value) throw new Error(`${FIXED_TIME_ENV} is not set — global.setup.ts did not run`);
  return new Date(value);
}

interface DocumentSummary {
  readonly id: string;
  readonly title: string;
  readonly createdAt: string;
}

/** Set by global.setup.ts — one API login per run, inherited by every worker. */
export const SMOKE_REVIEW_ID_ENV = 'E2E_SMOKE_REVIEW_ID';

async function apiLogin(request: APIRequestContext): Promise<string> {
  const response = await request.post('/api/v1/auth/login', {
    data: { usernameOrEmail: SEED_ADMIN.username, password: SEED_ADMIN.password },
  });
  if (!response.ok()) throw new Error(`API login failed: HTTP ${response.status()}`);
  const { accessToken } = (await response.json()) as { accessToken: string };
  return accessToken;
}

/** Looks the smoke review up by title; the id differs per deployment. */
export async function findSmokeReview(request: APIRequestContext): Promise<DocumentSummary> {
  const token = await apiLogin(request);
  const response = await request.get('/api/v1/documents', {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok()) throw new Error(`GET /api/v1/documents failed: HTTP ${response.status()}`);
  const { items } = (await response.json()) as { items: DocumentSummary[] };
  const review = items.find((item) => item.title === SMOKE_REVIEW_TITLE);
  if (!review) {
    throw new Error(
      `No "${SMOKE_REVIEW_TITLE}" on the backend — run scripts/smoke-test.sh against it first`,
    );
  }
  return review;
}

/** Signs in through the real login form. */
export async function signIn(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Email or username').fill(SEED_ADMIN.username);
  await page.getByRole('textbox', { name: 'Password' }).fill(SEED_ADMIN.password);
  await page.locator('form button[type=submit]').click();
  // Either the shell appears or the form reports why not — name the reason
  // (a rate limit, a wrong seed) instead of timing out on the URL.
  const outcome = page.getByRole('main').or(page.getByRole('alert'));
  await outcome.first().waitFor();
  const alert = page.getByRole('alert');
  if (await alert.count()) throw new Error(`Sign-in failed: ${await alert.first().innerText()}`);
  await baseExpect(page).toHaveURL(/\/$/);
}

interface WorkerFixtures {
  /**
   * One signed-in browser context per worker. The session lives in a rotating
   * refresh cookie (ADR-0026): every load spends the cookie and receives the
   * next one, so a stored state replayed into fresh contexts is good exactly
   * once. Pages of one context share the jar and follow the rotation.
   */
  signedInContext: BrowserContext;
  /** The smoke review's id, resolved once per run (global.setup.ts). */
  smokeReviewId: string;
}

export const test = base.extend<Record<never, never>, WorkerFixtures>({
  smokeReviewId: [
    // eslint-disable-next-line no-empty-pattern -- Playwright fixtures take the bag positionally
    async ({}, use) => {
      const id = process.env[SMOKE_REVIEW_ID_ENV];
      if (!id) throw new Error(`${SMOKE_REVIEW_ID_ENV} is not set — global.setup.ts did not run`);
      await use(id);
    },
    { scope: 'worker' },
  ],
  signedInContext: [
    async ({ browser }, use) => {
      const context = await browser.newContext({ baseURL: BASE_URL });
      const page = await context.newPage();
      await signIn(page);
      await page.close();
      await use(context);
      await context.close();
    },
    { scope: 'worker' },
  ],
  page: async ({ signedInContext, viewport }, use) => {
    const page = await signedInContext.newPage();
    // The SPA keeps device-local state — the dashboard's "continue where you
    // left off" strip, the persisted focus/split choice, panel widths, the
    // sidebar's collapse — in localStorage. Pages of one context would inherit
    // it from whichever test ran before, and a split-view test could open in
    // focus mode. Every page starts as a fresh device instead.
    await page.addInitScript(() => {
      try {
        localStorage.clear();
      } catch {
        // Storage may be unavailable; the app copes with that already.
      }
    });
    if (viewport) await page.setViewportSize(viewport);
    await use(page);
    await page.close();
  },
});

export const expect = baseExpect;

/**
 * Freezes the clock, then navigates and waits for the surface to settle: the
 * shell rendered, fonts loaded and no loading indicator left. Not
 * `networkidle` — the banner and the review queries poll, so the network is
 * never idle for long on the surfaces that matter most.
 */
export async function open(page: Page, path: string): Promise<void> {
  await page.clock.setFixedTime(fixedTime());
  await page.goto(path);
  await page.getByRole('main').waitFor();
  // The workspace is its PDF: the viewer mounts after the review query and
  // paints a canvas a couple of seconds later, and the toolbar's width — the
  // thing finding 5 is about — is only final once it has. Wait for it.
  if (WORKSPACE_PATH.test(path)) await page.locator('canvas').first().waitFor();
  await settle(page);
}

/** `/reviews/:id` with or without a query — not `/tasks`, not `/compare`. */
const WORKSPACE_PATH = /^\/reviews\/[^/?]+(\?.*)?$/;

/**
 * Waits until the surface is loaded and its layout has stopped moving: fonts
 * in, no indeterminate spinner or bar (MUI's, or a hand-rolled one such as
 * the viewer's, which has the role but no value), no skeleton, and `main`'s
 * scroll size unchanged over three samples 250 ms apart. Determinate bars
 * carry `aria-valuenow`: they are meters (review progress, team XP) and stay. The stability tail is what makes the overflow assertion
 * honest: a KPI row or a PDF canvas that lands after its query resolves
 * changes the width the check reads, and no indicator announces it.
 */
export async function settle(page: Page): Promise<void> {
  await page.waitForFunction(
    () =>
      document.fonts.status === 'loaded' &&
      !document.querySelector(
        '.MuiCircularProgress-indeterminate, .MuiLinearProgress-indeterminate, .MuiSkeleton-root, [role="progressbar"]:not([aria-valuenow])',
      ),
  );
  const sample = () =>
    page.evaluate(() => {
      const main = document.querySelector('main') ?? document.body;
      return `${main.scrollWidth}x${main.scrollHeight}:${document.querySelectorAll('canvas').length}`;
    });
  let previous = await sample();
  for (let stable = 0; stable < 2;) {
    await page.waitForTimeout(250);
    const current = await sample();
    stable = current === previous ? stable + 1 : 0;
    previous = current;
  }
}
