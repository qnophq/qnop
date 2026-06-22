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

const RATE_LIMITED = 'Too many attempts. Please try again later.';

/**
 * Maps an API/network error to a concise, user-facing message. Rate limits (429)
 * get specific text. A 400 is split by its error code: a body-validation failure
 * (`VALIDATION_ERROR`, e.g. the password policy) surfaces the caller's
 * context-specific fallback, while any other 400 — a consumed or expired
 * token/link in the reset flow — gets the link-expired text. Everything else
 * falls back to the caller's default. Never surfaces server internals.
 */
export function apiErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const status = error.response?.status;
    if (status === 429) return RATE_LIMITED;
    if (status === 400) {
      if (apiErrorCode(error) === 'VALIDATION_ERROR') return fallback;
      return 'The request is invalid or the link has expired.';
    }
  }
  if (error instanceof Error && error.message === 'RATE_LIMITED') {
    return RATE_LIMITED;
  }
  return fallback;
}

/**
 * The stable `code` from the uniform API error envelope (e.g. `EMAIL_TAKEN`,
 * `SELF_LOCKOUT`), or undefined when the error carries none. Callers map known
 * codes to localized messages instead of surfacing the server's English text.
 */
export function apiErrorCode(error: unknown): string | undefined {
  if (isAxiosError(error)) {
    const code = (error.response?.data as { code?: unknown } | undefined)?.code;
    if (typeof code === 'string') return code;
  }
  return undefined;
}
