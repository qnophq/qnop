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

import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import { applyLocale } from '../../i18n';
import { buildTheme } from '../../theme/theme';
import { useConfig } from '../../api/hooks/useConfig';
import { LoginPage } from './LoginPage';

vi.mock('../../api/hooks/useConfig', () => ({
  useConfig: vi.fn(),
}));

function renderLogin(initialEntry = '/login') {
  return render(
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

/** The sign-in page in German (issue #464): the catalog drives every visible string. */
describe('LoginPage in German', () => {
  beforeEach(async () => {
    vi.mocked(useConfig).mockReturnValue({
      data: {
        auth: {
          selfRegistrationEnabled: true,
          passwordResetEnabled: true,
          oidcProviders: [
            {
              id: 'p1',
              name: 'Acme SSO',
              loginUrl: '/oauth2/authorization/acme',
              iconKind: 'OIDC',
            },
          ],
        },
      },
    } as unknown as ReturnType<typeof useConfig>);
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the form, mode switch and provider buttons in German', () => {
    renderLogin();

    expect(screen.getByRole('heading', { name: 'Willkommen zurück' })).toBeInTheDocument();
    expect(screen.getByLabelText(/E-Mail oder Benutzername/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Passwort/, { selector: 'input' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Passwort vergessen?' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Anmelden' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Konto erstellen' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Mit Acme SSO anmelden' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Passwort anzeigen' })).toBeInTheDocument();
  });

  it('translates an OIDC redirect error, and follows a language switch while shown', async () => {
    renderLogin('/login?error=account_disabled');

    expect(
      screen.getByText('Dein Konto ist deaktiviert. Bitte wende dich an einen Administrator.'),
    ).toBeInTheDocument();

    await act(() => applyLocale('en'));

    expect(
      screen.getByText('Your account is disabled. Please contact an administrator.'),
    ).toBeInTheDocument();
  });
});
