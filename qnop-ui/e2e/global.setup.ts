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
import { BASE_URL, findSmokeReview, SMOKE_REVIEW_ID_ENV } from './fixtures';

/**
 * Resolves the smoke review once for the whole run. The login endpoint is
 * rate-limited per IP (ADR-0027, ten per minute), so the run spends one API
 * login here and one browser sign-in per worker — nothing more.
 */
export default async function globalSetup(): Promise<void> {
  const api = await request.newContext({ baseURL: BASE_URL });
  try {
    process.env[SMOKE_REVIEW_ID_ENV] = (await findSmokeReview(api)).id;
  } finally {
    await api.dispose();
  }
}
