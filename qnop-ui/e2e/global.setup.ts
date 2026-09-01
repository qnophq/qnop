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

import { request } from '@playwright/test';
import { BASE_URL, findSmokeReview, FIXED_TIME_ENV, SMOKE_REVIEW_ID_ENV } from './fixtures';

/**
 * Resolves the smoke review once for the whole run, and with it the moment
 * the browser clock is frozen at: one hour after the review was created. The
 * dashboard greets by the hour and every list shows relative times, and the
 * review is re-created by each smoke run — so a fixed calendar date would
 * read "in 3 days" on one run and "2 hours ago" on the next, while a moment
 * tied to the seed reads the same every time.
 *
 * The login endpoint is rate-limited per IP (ADR-0027, ten per minute), so
 * the run spends one API login here and one browser sign-in per worker —
 * nothing more.
 */
const ONE_HOUR_MS = 60 * 60 * 1000;

export default async function globalSetup(): Promise<void> {
  const api = await request.newContext({ baseURL: BASE_URL });
  try {
    const review = await findSmokeReview(api);
    process.env[SMOKE_REVIEW_ID_ENV] = review.id;
    process.env[FIXED_TIME_ENV] = new Date(
      new Date(review.createdAt).getTime() + ONE_HOUR_MS,
    ).toISOString();
  } finally {
    await api.dispose();
  }
}
