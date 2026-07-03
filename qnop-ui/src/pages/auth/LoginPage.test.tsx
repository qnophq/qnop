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
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { useAuthStore } from '../../stores/authStore';
import { useConfig } from '../../api/hooks/useConfig';
import { LoginPage } from './LoginPage';

vi.mock('../../api/hooks/useConfig', () => ({
  useConfig: vi.fn(),
}));

interface ConfigShape {
  selfRegistrationEnabled?: boolean;
  oidcProviders?: unknown[];
}

function mockConfig({ selfRegistrationEnabled = false, oidcProviders = [] }: ConfigShape = {}) {
  vi.mocked(useConfig).mockReturnValue({
    data: { auth: { selfRegistrationEnabled, oidcProviders } },
  } as unknown as ReturnType<typeof useConfig>);
}

/** Installs a controllable `login` action on the real store; captures its args. */
function mockLogin(impl: () => Promise<void>) {
  const login = vi.fn(impl);
  useAuthStore.setState({ login, passwordChangeRequired: false });
  return login;
}

function renderLogin(initialEntry = '/login') {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/change-password" element={<div data-testid="change-password-probe" />} />
          <Route path="/reviews" element={<div data-testid="reviews-probe" />} />
          <Route path="/" element={<div data-testid="root-probe" />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

function fillCredentials(user = 'alice', pass = 'sup3r-secret') {
  fireEvent.change(screen.getByLabelText(/Email or username/), { target: { value: user } });
  fireEvent.change(screen.getByLabelText(/^Password/), { target: { value: pass } });
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

beforeEach(() => {
  vi.clearAllMocks();
  mockConfig();
  // Reset the pieces of the real store the page reads.
  useAuthStore.setState({ login: vi.fn(async () => {}), passwordChangeRequired: false });
});

describe('LoginPage', () => {
  it('renders the credential form with required fields', () => {
    renderLogin();

    expect(screen.getByLabelText(/Email or username/)).toBeRequired();
    expect(screen.getByLabelText(/^Password/)).toBeRequired();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Forgot password/ })).toHaveAttribute(
      'href',
      '/forgot-password',
    );
  });

  it('shows the create-account switch only when self-registration is enabled', () => {
    mockConfig({ selfRegistrationEnabled: true });
    renderLogin();

    expect(screen.getByRole('link', { name: 'Create account' })).toBeInTheDocument();
  });

  it('hides the create-account switch when self-registration is disabled', () => {
    mockConfig({ selfRegistrationEnabled: false });
    renderLogin();

    expect(screen.queryByRole('link', { name: 'Create account' })).not.toBeInTheDocument();
  });

  it('submits the typed credentials to the store login action', async () => {
    const login = mockLogin(async () => {});
    renderLogin();

    fillCredentials('alice@example.com', 'hunter2');
    submit();

    await screen.findByTestId('root-probe');
    expect(login).toHaveBeenCalledWith('alice@example.com', 'hunter2');
  });

  it('redirects to the app root on a successful login without a target', async () => {
    mockLogin(async () => {});
    renderLogin();

    fillCredentials();
    submit();

    expect(await screen.findByTestId('root-probe')).toBeInTheDocument();
  });

  it('honours a safe ?from= redirect target', async () => {
    mockLogin(async () => {});
    renderLogin('/login?from=%2Freviews');

    fillCredentials();
    submit();

    expect(await screen.findByTestId('reviews-probe')).toBeInTheDocument();
  });

  it('ignores an off-origin ?from= target and falls back to the root', async () => {
    mockLogin(async () => {});
    renderLogin(`/login?from=${encodeURIComponent('//evil.example.com')}`);

    fillCredentials();
    submit();

    expect(await screen.findByTestId('root-probe')).toBeInTheDocument();
    expect(screen.queryByTestId('reviews-probe')).not.toBeInTheDocument();
  });

  it('redirects to change-password when the session forces a password change', async () => {
    mockLogin(async () => {
      useAuthStore.setState({ passwordChangeRequired: true });
    });
    renderLogin('/login?from=%2Freviews');

    fillCredentials();
    submit();

    // The forced password change wins over the ?from= target.
    expect(await screen.findByTestId('change-password-probe')).toBeInTheDocument();
    expect(screen.queryByTestId('reviews-probe')).not.toBeInTheDocument();
  });

  it('shows a credentials error and does not navigate on a failed login', async () => {
    mockLogin(async () => {
      throw new Error('INVALID_CREDENTIALS');
    });
    renderLogin();

    fillCredentials();
    submit();

    expect(
      await screen.findByText('Sign-in failed. Please check your credentials.'),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('root-probe')).not.toBeInTheDocument();
    // The submit button is usable again after the failure.
    expect(screen.getByRole('button', { name: 'Sign in' })).not.toBeDisabled();
  });

  it('surfaces a rate-limit message when the backend throttles login', async () => {
    mockLogin(async () => {
      throw new Error('RATE_LIMITED');
    });
    renderLogin();

    fillCredentials();
    submit();

    expect(await screen.findByText('Too many attempts. Please try again later.')).toBeInTheDocument();
  });

  it('disables the submit button while the login is in flight', async () => {
    let resolve: () => void = () => {};
    mockLogin(() => new Promise<void>((res) => (resolve = res)));
    renderLogin();

    fillCredentials();
    submit();

    const button = screen.getByRole('button', { name: 'Sign in' });
    await waitFor(() => expect(button).toBeDisabled());

    resolve();
    await screen.findByTestId('root-probe');
  });

  it.each([
    ['account_disabled', 'Your account is disabled. Please contact an administrator.'],
    [
      'email_missing',
      'The identity provider did not share an email address, which qnop requires for an account.',
    ],
  ])('maps the OIDC error code %s from the URL to its message', (code, message) => {
    renderLogin(`/login?error=${code}`);

    expect(screen.getByText(message)).toBeInTheDocument();
  });

  it('falls back to the generic OIDC message for an unknown error code', () => {
    renderLogin('/login?error=totally-unknown');

    expect(
      screen.getByText(
        'Sign-in via the identity provider failed. Please try again or contact an administrator.',
      ),
    ).toBeInTheDocument();
  });

  it('dismisses the OIDC error alert via its close button', () => {
    renderLogin('/login?error=oidc');

    const message =
      'Sign-in via the identity provider failed. Please try again or contact an administrator.';
    expect(screen.getByText(message)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /close/i }));
    expect(screen.queryByText(message)).not.toBeInTheDocument();
  });

  it('renders a provider button for each enabled OIDC provider', () => {
    mockConfig({
      oidcProviders: [
        { id: 'p1', name: 'Acme SSO', loginUrl: '/oauth2/authorization/acme', iconKind: 'OIDC' },
      ],
    });
    renderLogin();

    expect(screen.getByRole('link', { name: /Sign in with Acme SSO/ })).toHaveAttribute(
      'href',
      '/oauth2/authorization/acme',
    );
  });
});
