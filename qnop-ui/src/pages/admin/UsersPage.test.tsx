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
import type { AdminUserListResponse, AdminUserSummary } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { useAuthStore } from '../../stores/authStore';
import { UsersPage } from './UsersPage';

// The four page-owned mutations plus a mutable query-state object so each test
// can steer loading / error / empty / populated list responses.
const { updateMutate, deleteMutate, resetMutate, generateMutate, queryState } = vi.hoisted(() => ({
  updateMutate: vi.fn(),
  deleteMutate: vi.fn(),
  resetMutate: vi.fn(),
  generateMutate: vi.fn(),
  queryState: {
    data: undefined as AdminUserListResponse | undefined,
    isLoading: false,
    isFetching: false,
    isError: false,
  },
}));

vi.mock('../../api/hooks/useAdminUsers', () => ({
  useAdminUsers: () => queryState,
  useUpdateUser: () => ({ mutateAsync: updateMutate }),
  useDeleteUser: () => ({ mutateAsync: deleteMutate }),
  useSendUserPasswordReset: () => ({ mutateAsync: resetMutate }),
  useGenerateUserPassword: () => ({ mutateAsync: generateMutate }),
}));

// Stub the heavy child table down to one button per callback so the PAGE's own
// orchestration (which mutation runs, which dialog opens, which toast shows) is
// what gets exercised — the table's own rendering is covered by its own test.
vi.mock('../../components/admin/users/UsersTable', () => ({
  UsersTable: ({
    users,
    onSort,
    onEdit,
    onResetPassword,
    onGeneratePassword,
    onToggleEnabled,
    onDelete,
  }: {
    users: AdminUserSummary[];
    onSort: (field: string) => void;
    onEdit: (user: AdminUserSummary) => void;
    onResetPassword: (user: AdminUserSummary) => void;
    onGeneratePassword: (user: AdminUserSummary) => void;
    onToggleEnabled: (user: AdminUserSummary) => void;
    onDelete: (user: AdminUserSummary) => void;
  }): ReactNode => (
    <div data-testid="users-table">
      {users.length === 0 && <span>stub-no-users</span>}
      <button onClick={() => onSort('role')}>stub-sort-role</button>
      {users.map((user) => (
        <div key={user.id}>
          <span>{user.displayName}</span>
          <button onClick={() => onEdit(user)}>edit-{user.id}</button>
          <button onClick={() => onResetPassword(user)}>reset-{user.id}</button>
          <button onClick={() => onGeneratePassword(user)}>generate-{user.id}</button>
          <button onClick={() => onToggleEnabled(user)}>toggle-{user.id}</button>
          <button onClick={() => onDelete(user)}>delete-{user.id}</button>
        </div>
      ))}
    </div>
  ),
}));

vi.mock('../../components/admin/users/UserFormDialog', () => ({
  UserFormDialog: ({
    open,
    mode,
    user,
    onClose,
  }: {
    open: boolean;
    mode: 'create' | 'edit';
    user?: AdminUserSummary;
    onClose: () => void;
  }): ReactNode =>
    open ? (
      <div>
        <span>stub-user-form-{mode}</span>
        {user && <span>stub-editing-{user.displayName}</span>}
        <button onClick={onClose}>stub-close-form</button>
      </div>
    ) : null,
}));

vi.mock('../../components/admin/users/ResetLinkDialog', () => ({
  ResetLinkDialog: ({
    open,
    displayName,
    url,
  }: {
    open: boolean;
    displayName: string;
    url: string;
  }): ReactNode =>
    open ? (
      <div>
        stub-reset-link {displayName} {url}
      </div>
    ) : null,
}));

vi.mock('../../components/admin/users/GeneratedPasswordDialog', () => ({
  GeneratedPasswordDialog: ({
    open,
    displayName,
    password,
  }: {
    open: boolean;
    displayName: string;
    password: string;
  }): ReactNode =>
    open ? (
      <div>
        stub-generated {displayName} {password}
      </div>
    ) : null,
}));

vi.mock('../../components/admin/ConfirmDialog', () => ({
  ConfirmDialog: ({
    open,
    message,
    onConfirm,
    onClose,
  }: {
    open: boolean;
    message: string;
    onConfirm: () => void;
    onClose: () => void;
  }): ReactNode =>
    open ? (
      <div role="dialog">
        <p>{message}</p>
        <button onClick={onConfirm}>stub-confirm-delete</button>
        <button onClick={onClose}>stub-cancel-delete</button>
      </div>
    ) : null,
}));

const ADA: AdminUserSummary = {
  id: 'ada',
  displayName: 'Ada Admin',
  email: 'ada@example.com',
  role: 'ADMIN',
  source: 'INTERNAL',
  enabled: true,
  passwordChangeRequired: false,
  createdAt: '2026-01-01T00:00:00Z',
};

const BEN: AdminUserSummary = {
  id: 'ben',
  displayName: 'Ben Member',
  email: 'ben@example.com',
  role: 'MEMBER',
  source: 'INTERNAL',
  enabled: false,
  passwordChangeRequired: false,
  createdAt: '2026-01-02T00:00:00Z',
};

const LIST: AdminUserListResponse = { items: [ADA, BEN], total: 2, page: 0, size: 20 };

/** A minimal axios-shaped error carrying the API's uniform `code` envelope. */
function conflictError(code: string, status = 409) {
  return { isAxiosError: true, response: { status, data: { code } } };
}

beforeEach(() => {
  updateMutate.mockReset().mockResolvedValue({});
  deleteMutate.mockReset().mockResolvedValue(undefined);
  resetMutate.mockReset().mockResolvedValue({ emailSent: true });
  generateMutate.mockReset().mockResolvedValue({ password: 'hunter2' });
  queryState.data = LIST;
  queryState.isLoading = false;
  queryState.isFetching = false;
  queryState.isError = false;
  useAuthStore.setState({ userId: 'me' });
});

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const renderPage = () => render(<UsersPage />, { wrapper });

describe('UsersPage', () => {
  it('renders the header and the current page of users', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Users' })).toBeTruthy();
    expect(screen.getByText('Ada Admin')).toBeTruthy();
    expect(screen.getByText('Ben Member')).toBeTruthy();
  });

  it('shows the loading placeholder while the first page is loading', () => {
    queryState.isLoading = true;
    renderPage();

    expect(screen.getByText('Loading…')).toBeTruthy();
  });

  it('shows an error alert instead of the table when the list fails to load', () => {
    queryState.isError = true;
    renderPage();

    expect(screen.getByText('The user list could not be loaded.')).toBeTruthy();
    expect(screen.queryByTestId('users-table')).toBeNull();
  });

  it('renders the empty-state row when there are no users', () => {
    queryState.data = { items: [], total: 0, page: 0, size: 20 };
    renderPage();

    expect(screen.getByText('stub-no-users')).toBeTruthy();
  });

  it('opens the create dialog from the Add user button', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Add user' }));

    expect(screen.getByText('stub-user-form-create')).toBeTruthy();
  });

  it('opens the edit dialog seeded with the chosen user', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'edit-ada' }));

    expect(screen.getByText('stub-user-form-edit')).toBeTruthy();
    expect(screen.getByText('stub-editing-Ada Admin')).toBeTruthy();
  });

  it('clears the search box via its clear button', () => {
    renderPage();

    const search = screen.getByLabelText('Search users') as HTMLInputElement;
    fireEvent.change(search, { target: { value: 'ada' } });
    expect(search.value).toBe('ada');

    fireEvent.click(screen.getByRole('button', { name: 'Clear search' }));
    expect(search.value).toBe('');
  });

  it('toggles a user by patching the inverse enabled flag', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'toggle-ada' }));

    await waitFor(() =>
      expect(updateMutate).toHaveBeenCalledWith({ id: 'ada', request: { enabled: false } }),
    );
  });

  it('maps a LAST_ADMIN conflict on toggle to its curated toast', async () => {
    updateMutate.mockRejectedValue(conflictError('LAST_ADMIN'));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'toggle-ada' }));

    expect(await screen.findByText('At least one enabled admin must remain.')).toBeTruthy();
  });

  it('falls back to a generic toast when a toggle error carries no known code', async () => {
    updateMutate.mockRejectedValue(new Error('boom'));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'toggle-ben' }));

    expect(await screen.findByText('Could not update Ben Member.')).toBeTruthy();
  });

  it('confirms a delete and reports success', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'delete-ben' }));
    // The confirm dialog opens with the target's name in its message.
    expect(screen.getByRole('dialog')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'stub-confirm-delete' }));

    await waitFor(() => expect(deleteMutate).toHaveBeenCalledWith('ben'));
    expect(await screen.findByText('User “Ben Member” deleted.')).toBeTruthy();
  });

  it('surfaces a delete failure as an error toast', async () => {
    deleteMutate.mockRejectedValue(new Error('nope'));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'delete-ben' }));
    fireEvent.click(screen.getByRole('button', { name: 'stub-confirm-delete' }));

    expect(await screen.findByText('Could not delete the user.')).toBeTruthy();
  });

  it('reports an emailed password reset as a success toast', async () => {
    resetMutate.mockResolvedValue({ emailSent: true });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'reset-ben' }));

    await waitFor(() => expect(resetMutate).toHaveBeenCalledWith('ben'));
    expect(
      await screen.findByText('Password reset emailed to ben@example.com. Sessions revoked.'),
    ).toBeTruthy();
  });

  it('shows the reset-link dialog when email delivery falls back to a link', async () => {
    resetMutate.mockResolvedValue({ emailSent: false, resetUrl: 'https://q/reset?t=1' });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'reset-ada' }));

    expect(await screen.findByText(/stub-reset-link Ada Admin/)).toBeTruthy();
    expect(screen.getByText(/https:\/\/q\/reset\?t=1/)).toBeTruthy();
  });

  it('warns when a reset returns neither an email nor a link', async () => {
    resetMutate.mockResolvedValue({ emailSent: false });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'reset-ada' }));

    expect(
      await screen.findByText('Reset triggered for Ada Admin, but no link was returned.'),
    ).toBeTruthy();
  });

  it('surfaces a failed reset as an error toast', async () => {
    resetMutate.mockRejectedValue(new Error('down'));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'reset-ada' }));

    expect(await screen.findByText('The reset could not be triggered.')).toBeTruthy();
  });

  it('shows the generated password in its dialog', async () => {
    generateMutate.mockResolvedValue({ password: 's3cr3t' });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'generate-ada' }));

    await waitFor(() => expect(generateMutate).toHaveBeenCalledWith('ada'));
    expect(await screen.findByText(/stub-generated Ada Admin s3cr3t/)).toBeTruthy();
  });

  it('surfaces a failed password generation as an error toast', async () => {
    generateMutate.mockRejectedValue(new Error('nope'));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'generate-ada' }));

    expect(await screen.findByText('A password could not be generated.')).toBeTruthy();
  });
});
