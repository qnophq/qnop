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
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { renderWithProviders } from '../../test/renderWithProviders';
import { fetchVerificationUsername, verifyEmail } from '../../api/auth';
import { useConfig } from '../../api/hooks/useConfig';
import { VerifyEmailPage } from './VerifyEmailPage';

vi.mock('../../api/auth', () => ({ verifyEmail: vi.fn(), fetchVerificationUsername: vi.fn() }));
vi.mock('../../api/hooks/useConfig', () => ({ useConfig: vi.fn() }));
const verifyEmailFn = vi.mocked(verifyEmail);
const fetchUsernameFn = vi.mocked(fetchVerificationUsername);
const useConfigMock = vi.mocked(useConfig);

const STRONG = 'brand-new-strong-pw';

function mockConfig(selfRegistrationEnabled: boolean) {
  useConfigMock.mockReturnValue({
    data: { auth: { selfRegistrationEnabled } },
    isLoading: false,
  } as unknown as ReturnType<typeof useConfig>);
}

function apiError(code: string): AxiosError {
  const headers = new AxiosHeaders();
  return new AxiosError('Bad Request', 'ERR_BAD_REQUEST', { headers }, undefined, {
    status: 400,
    statusText: 'Bad Request',
    headers: {},
    config: { headers },
    data: { code, message: 'x' },
  });
}

async function submitPassword(password: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/^password/i), password);
  await user.click(screen.getByRole('button', { name: /confirm and activate/i }));
  return user;
}

function renderPage(token = 'good-token') {
  renderWithProviders(<VerifyEmailPage />, { initialEntries: [`/verify-email?token=${token}`] });
}

beforeEach(() => {
  vi.clearAllMocks();
  mockConfig(true);
});

describe('VerifyEmailPage', () => {
  it('confirms the registration with its password', async () => {
    verifyEmailFn.mockResolvedValue();
    renderPage();

    // Nothing is sent before the password is entered.
    expect(verifyEmailFn).not.toHaveBeenCalled();
    await submitPassword('my-password-1');

    expect(await screen.findByText(/your email address is confirmed/i)).toBeInTheDocument();
    expect(verifyEmailFn).toHaveBeenCalledWith('good-token', 'my-password-1');
  });

  it('on a mismatch offers to set own credentials, prefilled with the registration username', async () => {
    verifyEmailFn.mockRejectedValueOnce(apiError('VERIFICATION_PASSWORD_MISMATCH'));
    fetchUsernameFn.mockResolvedValue('squatter');
    renderPage();

    const user = await submitPassword('mine-1234');
    expect(await screen.findByText(/doesn't match the one chosen at sign-up/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /set my own username and password/i }));

    const username = await screen.findByLabelText(/^username/i);
    expect(username).toHaveValue('squatter');
    await user.clear(username);
    await user.type(username, 'owner');
    await user.type(screen.getByLabelText(/^new password/i), STRONG);
    await user.type(screen.getByLabelText(/^confirm password/i), STRONG);
    verifyEmailFn.mockResolvedValueOnce();
    await user.click(screen.getByRole('button', { name: /activate with these details/i }));

    expect(await screen.findByText(/your email address is confirmed/i)).toBeInTheDocument();
    expect(verifyEmailFn).toHaveBeenLastCalledWith('good-token', STRONG, {
      setNew: true,
      username: 'owner',
    });
  });

  it('asks for another username when it is taken', async () => {
    verifyEmailFn.mockRejectedValueOnce(apiError('VERIFICATION_USERNAME_TAKEN'));
    fetchUsernameFn.mockResolvedValue('wanted');
    renderPage();

    await submitPassword('my-password-1');

    expect(await screen.findByText(/already taken/i)).toBeInTheDocument();
    expect(await screen.findByLabelText(/^username/i)).toHaveValue('wanted');
  });

  it('shows the dead-link error when the token is rejected', async () => {
    verifyEmailFn.mockRejectedValue(apiError('INVALID_VERIFICATION_TOKEN'));
    renderPage('stale');

    await submitPassword('my-password-1');

    expect(await screen.findByText(/link is invalid or expired/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /confirm and activate/i })).toBeNull();
    expect(screen.getByText(/stop working once the account is activated/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /register again/i })).toHaveAttribute(
      'href',
      '/register',
    );
  });

  it('offers no register link where self-registration is off', () => {
    mockConfig(false);
    renderWithProviders(<VerifyEmailPage />, { initialEntries: ['/verify-email'] });

    expect(screen.getByText(/link is invalid or expired/i)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /register again/i })).toBeNull();
  });

  it('shows an error and never calls the API when no token is present', () => {
    renderWithProviders(<VerifyEmailPage />, { initialEntries: ['/verify-email'] });

    expect(screen.getByText(/link is invalid or expired/i)).toBeInTheDocument();
    expect(verifyEmailFn).not.toHaveBeenCalled();
  });
});
