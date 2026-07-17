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
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { OidcProviderDto } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { OidcProvidersPage } from './OidcProvidersPage';

const { updateMutate, deleteMutate, useOidcProvidersMock } = vi.hoisted(() => ({
  updateMutate: vi.fn(),
  deleteMutate: vi.fn(),
  useOidcProvidersMock: vi.fn(),
}));

const PROVIDERS: OidcProviderDto[] = [
  {
    id: 'p-1',
    name: 'Keycloak',
    providerType: 'OIDC',
    enabled: true,
    clientId: 'qnop',
    hasClientSecret: true,
    scope: 'openid profile email',
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'p-2',
    name: 'GitHub',
    providerType: 'GITHUB',
    enabled: false,
    clientId: 'gh-app',
    hasClientSecret: true,
    scope: 'read:user user:email',
    createdAt: '2026-01-02T00:00:00Z',
  },
];

vi.mock('../../api/hooks/useOidcProviders', () => ({
  useOidcProviders: () => useOidcProvidersMock(),
  useUpdateOidcProvider: () => ({ mutateAsync: updateMutate }),
  useDeleteOidcProvider: () => ({ mutateAsync: deleteMutate }),
}));

vi.mock('../../components/admin/oidc/OidcProvidersTable', () => ({
  OidcProvidersTable: ({
    providers,
    onToggleEnabled,
    onDelete,
  }: {
    providers: OidcProviderDto[];
    onToggleEnabled: (p: OidcProviderDto) => void;
    onDelete: (p: OidcProviderDto) => void;
  }) => (
    <div>
      {providers.map((provider) => (
        <div key={provider.id}>
          <span>{provider.name}</span>
          <button onClick={() => onToggleEnabled(provider)}>toggle-{provider.id}</button>
          <button onClick={() => onDelete(provider)}>delete-{provider.id}</button>
        </div>
      ))}
    </div>
  ),
}));

vi.mock('../../components/admin/oidc/OidcProviderFormDialog', () => ({
  OidcProviderFormDialog: ({ open, mode }: { open: boolean; mode: string }) =>
    open ? <div>form-dialog-open:{mode}</div> : null,
}));

vi.mock('../../components/admin/ConfirmDialog', () => ({
  ConfirmDialog: ({ open, onConfirm }: { open: boolean; onConfirm: () => void }) =>
    open ? <button onClick={onConfirm}>confirm-delete</button> : null,
}));

beforeEach(() => {
  updateMutate.mockReset().mockResolvedValue(undefined);
  deleteMutate.mockReset().mockResolvedValue(undefined);
  useOidcProvidersMock.mockReset().mockReturnValue({
    data: { providers: PROVIDERS },
    isLoading: false,
    isFetching: false,
    isError: false,
  });
});

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const renderPage = () => render(<OidcProvidersPage />, { wrapper });

describe('OidcProvidersPage', () => {
  it('lists the configured providers', () => {
    renderPage();

    expect(screen.getByText('Keycloak')).toBeTruthy();
    expect(screen.getByText('GitHub')).toBeTruthy();
  });

  it('shows an error alert when the providers cannot be loaded', () => {
    useOidcProvidersMock.mockReturnValue({
      data: undefined,
      isLoading: false,
      isFetching: false,
      isError: true,
    });
    renderPage();

    expect(screen.getByText('The providers could not be loaded.')).toBeTruthy();
    expect(screen.queryByText('Keycloak')).toBeNull();
  });

  it('toggles a provider and surfaces a success toast', async () => {
    renderPage();

    // Keycloak is enabled → toggling requests `enabled: false`.
    fireEvent.click(screen.getByRole('button', { name: 'toggle-p-1' }));

    await waitFor(() =>
      expect(updateMutate).toHaveBeenCalledWith({ id: 'p-1', request: { enabled: false } }),
    );
    expect(await screen.findByText('Keycloak disabled.')).toBeTruthy();
  });

  it('reports a failed toggle as an error toast', async () => {
    updateMutate.mockRejectedValue(new Error('boom'));
    renderPage();

    // GitHub is disabled → toggling requests `enabled: true`.
    fireEvent.click(screen.getByRole('button', { name: 'toggle-p-2' }));

    await waitFor(() =>
      expect(updateMutate).toHaveBeenCalledWith({ id: 'p-2', request: { enabled: true } }),
    );
    const alert = await screen.findByText('The change could not be saved.');
    expect(alert.closest('.MuiAlert-colorError')).toBeTruthy();
  });

  it('deletes a provider after confirmation and surfaces a success toast', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'delete-p-2' }));
    fireEvent.click(await screen.findByRole('button', { name: 'confirm-delete' }));

    await waitFor(() => expect(deleteMutate).toHaveBeenCalledWith('p-2'));
    expect(await screen.findByText('GitHub deleted.')).toBeTruthy();
  });

  it('reports a failed delete as an error toast', async () => {
    deleteMutate.mockRejectedValue(new Error('boom'));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'delete-p-1' }));
    fireEvent.click(await screen.findByRole('button', { name: 'confirm-delete' }));

    await waitFor(() => expect(deleteMutate).toHaveBeenCalledWith('p-1'));
    const alert = await screen.findByText('The provider could not be deleted.');
    expect(alert.closest('.MuiAlert-colorError')).toBeTruthy();
  });

  it('opens the create dialog from the "Add provider" button', () => {
    renderPage();

    expect(screen.queryByText(/form-dialog-open/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Add provider' }));

    expect(screen.getByText('form-dialog-open:create')).toBeTruthy();
  });
});
