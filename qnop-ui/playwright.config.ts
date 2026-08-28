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

import { defineConfig, devices } from '@playwright/test';

/**
 * The responsive regression net (issue #725, from the #461 audit).
 *
 * Runs against a real backend: locally the docker-compose smoke stack (or a
 * `bootRun`), in CI the stack the smoke job has already built, seeded and
 * exercised. The tests drive the Vite dev server, which proxies the API to
 * `QNOP_API_URL` — so the SPA under test is the working tree, not a bundle,
 * and the same backend serves both the smoke script and the browser.
 */
const apiUrl = process.env.QNOP_API_URL ?? 'http://localhost:8080';
const port = 5173;
const baseURL = `http://localhost:${port}`;
process.env.E2E_BASE_URL = baseURL;

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global.setup.ts',
  // Baselines sit beside the specs, named by test and width only — no platform
  // or browser suffix, because the suite runs on Linux Chromium everywhere and
  // one set of images is the point.
  snapshotPathTemplate: '{testDir}/__screenshots__/{testFilePath}/{arg}{ext}',
  fullyParallel: true,
  // The dev server transforms each route on first request; under several
  // workers that cold start alone can take most of the default 30 s.
  timeout: 60_000,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  expect: {
    toHaveScreenshot: {
      // Antialiasing differs a little between machines; a layout regression
      // moves whole boxes and is well above this. Not looser: at 2 % a
      // 320-wide page could swap a rendered PDF for a spinner unnoticed.
      maxDiffPixelRatio: 0.005,
      animations: 'disabled',
      caret: 'hide',
    },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `pnpm exec vite --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    env: { QNOP_API_URL: apiUrl },
    timeout: 120_000,
  },
});
