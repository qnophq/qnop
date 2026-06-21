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

import { AxiosError, type AxiosResponse } from 'axios';
import { describe, expect, it } from 'vitest';
import { apiErrorMessage } from './apiError';

function axiosWithStatus(status: number): AxiosError {
  return new AxiosError('x', 'ERR', undefined, undefined, { status } as AxiosResponse);
}

describe('apiErrorMessage', () => {
  it('maps a 429 to a rate-limit message', () => {
    expect(apiErrorMessage(axiosWithStatus(429), 'fallback')).toMatch(/Versuche/i);
  });

  it('maps a 400 to an invalid/expired message', () => {
    expect(apiErrorMessage(axiosWithStatus(400), 'fallback')).toMatch(/ungültig|abgelaufen/i);
  });

  it('maps the RATE_LIMITED sentinel error to the rate-limit message', () => {
    expect(apiErrorMessage(new Error('RATE_LIMITED'), 'fallback')).toMatch(/Versuche/i);
  });

  it('falls back for unknown errors', () => {
    expect(apiErrorMessage(new Error('boom'), 'fallback')).toBe('fallback');
    expect(apiErrorMessage(axiosWithStatus(500), 'fallback')).toBe('fallback');
  });
});
