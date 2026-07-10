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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import { apiErrorCode, apiErrorMessage } from '../utils/apiError';
import { authApi, authPasswordResetApi, authRegistrationApi } from './config';
import { changePassword, forgotPassword, register, resetPassword, verifyEmail } from './auth';

vi.mock('./config', () => ({
  authApi: { changePassword: vi.fn() },
  authRegistrationApi: { register: vi.fn(), verifyEmail: vi.fn() },
  authPasswordResetApi: { forgotPassword: vi.fn(), resetPassword: vi.fn() },
}));

const registerFn = vi.mocked(authRegistrationApi.register);
const verifyEmailFn = vi.mocked(authRegistrationApi.verifyEmail);
const forgotPasswordFn = vi.mocked(authPasswordResetApi.forgotPassword);
const resetPasswordFn = vi.mocked(authPasswordResetApi.resetPassword);
const changePasswordFn = vi.mocked(authApi.changePassword);

function axiosError(status: number, data?: unknown): AxiosError {
  return new AxiosError('boom', 'ERR', undefined, undefined, { status, data } as AxiosResponse);
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('auth API wrappers', () => {
  it('register forwards the fields under registerRequest and resolves on 200', async () => {
    registerFn.mockResolvedValue({ status: 200 } as never);
    const input = { username: 'jo', email: 'jo@x.io', password: 'pw', displayName: 'Jo' };

    await expect(register(input)).resolves.toBeUndefined();
    expect(registerFn).toHaveBeenCalledWith({ registerRequest: input });
  });

  it('verifyEmail forwards the token', async () => {
    verifyEmailFn.mockResolvedValue({ status: 200 } as never);

    await verifyEmail('tok-123');

    expect(verifyEmailFn).toHaveBeenCalledWith({ token: 'tok-123' });
  });

  it('forgotPassword wraps the email in the request body', async () => {
    forgotPasswordFn.mockResolvedValue({ status: 200 } as never);

    await forgotPassword('jo@x.io');

    expect(forgotPasswordFn).toHaveBeenCalledWith({ forgotPasswordRequest: { email: 'jo@x.io' } });
  });

  it('resetPassword forwards the token and new password', async () => {
    resetPasswordFn.mockResolvedValue({ status: 200 } as never);

    await resetPassword('tok', 'new-pass');

    expect(resetPasswordFn).toHaveBeenCalledWith({
      resetPasswordRequest: { token: 'tok', newPassword: 'new-pass' },
    });
  });

  it('changePassword forwards the current and new password', async () => {
    changePasswordFn.mockResolvedValue({ status: 204 } as never);

    await changePassword('old', 'new');

    expect(changePasswordFn).toHaveBeenCalledWith({
      changePasswordRequest: { currentPassword: 'old', newPassword: 'new' },
    });
  });

  it('propagates a 409 conflict so the caller can read the code', async () => {
    registerFn.mockRejectedValue(axiosError(409, { code: 'EMAIL_TAKEN' }));

    const error = await register({
      username: 'jo',
      email: 'taken@x.io',
      password: 'pw',
      displayName: 'Jo',
    }).catch((e: unknown) => e);

    expect(apiErrorCode(error)).toBe('EMAIL_TAKEN');
  });

  it('a 429 propagates and extracts the rate-limit message', async () => {
    forgotPasswordFn.mockRejectedValue(axiosError(429));

    const error = await forgotPassword('jo@x.io').catch((e: unknown) => e);

    expect(apiErrorMessage(error, 'fallback')).toBe('Too many attempts. Please try again later.');
  });

  it('a 400 with an expired token surfaces the link-expired message', async () => {
    resetPasswordFn.mockRejectedValue(axiosError(400, { code: 'INVALID_TOKEN' }));

    const error = await resetPassword('stale', 'new-pass').catch((e: unknown) => e);

    expect(apiErrorMessage(error, 'fallback')).toBe(
      'The request is invalid or the link has expired.',
    );
  });

  it('a 400 password-policy failure surfaces the caller fallback', async () => {
    changePasswordFn.mockRejectedValue(axiosError(400, { code: 'VALIDATION_ERROR' }));

    const error = await changePassword('old', 'weak').catch((e: unknown) => e);

    expect(apiErrorMessage(error, 'too weak')).toBe('too weak');
  });

  it('a network error (no response) falls back to the caller message', async () => {
    registerFn.mockRejectedValue(new AxiosError('Network Error', 'ERR_NETWORK'));

    const error = await register({
      username: 'jo',
      email: 'jo@x.io',
      password: 'pw',
      displayName: 'Jo',
    }).catch((e: unknown) => e);

    expect(apiErrorMessage(error, 'try again')).toBe('try again');
  });
});
