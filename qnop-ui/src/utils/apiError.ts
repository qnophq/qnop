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

import { isAxiosError } from 'axios';

const RATE_LIMITED = 'Zu viele Versuche. Bitte versuche es später erneut.';

/**
 * Maps an API/network error to a concise, user-facing German message. Rate
 * limits (429) and bad requests (400) get specific text; everything else falls
 * back to the caller's default. Never surfaces server internals.
 */
export function apiErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const status = error.response?.status;
    if (status === 429) return RATE_LIMITED;
    if (status === 400) return 'Die Anfrage ist ungültig oder der Link ist abgelaufen.';
  }
  if (error instanceof Error && error.message === 'RATE_LIMITED') {
    return RATE_LIMITED;
  }
  return fallback;
}
