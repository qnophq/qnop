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

import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { AdminUserListResponse, AdminUserSummary } from '../../api/generated';
import { applyLocale } from '../../i18n';
import { buildTheme } from '../../theme/theme';
import { UsersPage } from './UsersPage';

const { queryState } = vi.hoisted(() => ({
  queryState: {
    data: undefined as AdminUserListResponse | undefined,
    isLoading: false,
    isFetching: false,
    isError: false,
  },
}));

vi.mock('../../api/hooks/useAdminUsers', () => ({
  useAdminUsers: () => queryState,
  useCreateUser: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useUpdateUser: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useDeleteUser: () => ({ mutateAsync: vi.fn() }),
  useSendUserPasswordReset: () => ({ mutateAsync: vi.fn() }),
  useGenerateUserPassword: () => ({ mutateAsync: vi.fn() }),
}));

const ADA: AdminUserSummary = {
  id: 'ada',
  displayName: 'Ada Admin',
  email: 'ada@example.com',
  role: 'ADMIN',
  source: 'INTERNAL',
  enabled: true,
  passwordChangeRequired: true,
  createdAt: '2026-01-01T00:00:00Z',
};

function renderPage(): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrap = (ui: ReactNode) => (
    <QueryClientProvider client={client}>
      <ThemeProvider theme={buildTheme('light', 'de')}>
        <MemoryRouter>{ui}</MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
  render(wrap(<UsersPage />));
}

/** The admin user management in German (issue #836): page chrome, table, plurals and dialogs. */
describe('users page in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
    queryState.data = { items: [ADA], total: 1, page: 0, size: 20 };
    queryState.isError = false;
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the header, filters and table in German', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Benutzer' })).toBeInTheDocument();
    expect(screen.getByText('Verwalte Konten, Rollen und Zugriffe.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Benutzer hinzufügen/ })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Letzte Anmeldung' })).toBeInTheDocument();
    expect(screen.getByText('Passwortänderung')).toBeInTheDocument();
    expect(screen.getByText('Lokal')).toBeInTheDocument();
    // Interpolation: the row's action button names the user.
    expect(screen.getByRole('button', { name: 'Aktionen für Ada Admin' })).toBeInTheDocument();
    expect(screen.getByText('Benutzer pro Seite')).toBeInTheDocument();
  });

  it('pluralises the seat ceiling', () => {
    queryState.data = { ...queryState.data!, seatLimit: 1, seatsUsed: 1 };
    renderPage();

    expect(
      screen.getByText('Verwalte Konten, Rollen und Zugriffe. 1 von 1 Platz belegt.'),
    ).toBeInTheDocument();
    expect(screen.getByText(/für 1 Konto konfiguriert und enthält 1\./)).toBeInTheDocument();
  });

  it('uses the plural form for several seats', () => {
    queryState.data = { ...queryState.data!, seatLimit: 5, seatsUsed: 2 };
    renderPage();

    expect(
      screen.getByText('Verwalte Konten, Rollen und Zugriffe. 2 von 5 Plätzen belegt.'),
    ).toBeInTheDocument();
  });

  it('opens the delete confirmation with the user name in German quotes', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Aktionen für Ada Admin' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Löschen' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Benutzer löschen')).toBeInTheDocument();
    expect(within(dialog).getByText(/^„Ada Admin“ endgültig löschen\?/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Abbrechen' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Löschen' })).toBeInTheDocument();
  });

  it('validates the add-user form in German', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /Benutzer hinzufügen/ }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Erstellen' }));

    expect(
      within(dialog).getByText('Ein vollständiger Name ist erforderlich.'),
    ).toBeInTheDocument();
    expect(within(dialog).getByText('Eine E-Mail-Adresse ist erforderlich.')).toBeInTheDocument();
  });
});
