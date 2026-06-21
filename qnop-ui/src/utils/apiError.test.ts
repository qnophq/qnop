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
import { apiErrorCode, apiErrorMessage } from './apiError';

function axiosWithStatus(status: number): AxiosError {
  return new AxiosError('x', 'ERR', undefined, undefined, { status } as AxiosResponse);
}

function axiosWithBody(status: number, data: unknown): AxiosError {
  return new AxiosError('x', 'ERR', undefined, undefined, { status, data } as AxiosResponse);
}

describe('apiErrorMessage', () => {
  it('maps a 429 to a rate-limit message', () => {
    expect(apiErrorMessage(axiosWithStatus(429), 'fallback')).toMatch(/attempts/i);
  });

  it('maps a 400 to an invalid/expired message', () => {
    expect(apiErrorMessage(axiosWithStatus(400), 'fallback')).toMatch(/invalid|expired/i);
  });

  it('maps the RATE_LIMITED sentinel error to the rate-limit message', () => {
    expect(apiErrorMessage(new Error('RATE_LIMITED'), 'fallback')).toMatch(/attempts/i);
  });

  it('falls back for unknown errors', () => {
    expect(apiErrorMessage(new Error('boom'), 'fallback')).toBe('fallback');
    expect(apiErrorMessage(axiosWithStatus(500), 'fallback')).toBe('fallback');
  });
});

describe('apiErrorCode', () => {
  it('extracts the code from the error envelope', () => {
    expect(apiErrorCode(axiosWithBody(409, { code: 'EMAIL_TAKEN' }))).toBe('EMAIL_TAKEN');
  });

  it('returns undefined when there is no code or it is not an axios error', () => {
    expect(apiErrorCode(axiosWithBody(409, { message: 'x' }))).toBeUndefined();
    expect(apiErrorCode(axiosWithStatus(500))).toBeUndefined();
    expect(apiErrorCode(new Error('boom'))).toBeUndefined();
  });
});
