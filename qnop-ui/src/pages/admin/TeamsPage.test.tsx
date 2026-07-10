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
import { MemoryRouter } from 'react-router-dom';
import type { AdminTeamListResponse, AdminTeamSummary } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { TeamsPage } from './TeamsPage';

type TeamsHookResult = {
  data: AdminTeamListResponse | undefined;
  isLoading: boolean;
  isFetching: boolean;
  isError: boolean;
};

const { teamsRef, deleteMutate, navigateSpy } = vi.hoisted(() => ({
  teamsRef: {
    current: {
      data: undefined,
      isLoading: false,
      isFetching: false,
      isError: false,
    } as TeamsHookResult,
  },
  deleteMutate: vi.fn(),
  navigateSpy: vi.fn(),
}));

const TEAMS: AdminTeamSummary[] = [
  {
    id: 'team-1',
    name: 'Alpha',
    description: 'First team',
    enabled: true,
    memberCount: 3,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
  },
  {
    id: 'team-2',
    name: 'Beta',
    description: '',
    enabled: false,
    memberCount: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
  },
];

const LIST: AdminTeamListResponse = { items: TEAMS, total: TEAMS.length, page: 0, size: 20 };

const setTeams = (partial: Partial<TeamsHookResult>) => {
  teamsRef.current = { ...teamsRef.current, ...partial };
};

vi.mock('../../api/hooks/useTeams', () => ({
  useTeams: () => teamsRef.current,
  useDeleteTeam: () => ({ mutateAsync: deleteMutate, isPending: false }),
}));

vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigateSpy,
}));

// Stub the create/edit dialog: it owns its own mutations, so here we only expose
// enough of the page's orchestration (open flag, mode, target team) to assert on.
vi.mock('../../components/admin/teams/TeamFormDialog', () => ({
  TeamFormDialog: (props: {
    open: boolean;
    mode: 'create' | 'edit';
    team?: AdminTeamSummary;
    onClose: () => void;
  }) =>
    props.open ? (
      <div data-testid="team-form-dialog">
        <span>form-mode:{props.mode}</span>
        {props.team ? <span>form-team:{props.team.name}</span> : null}
        <button type="button" onClick={props.onClose}>
          close-form
        </button>
      </div>
    ) : null,
}));

// Stub the confirm dialog so we can drive the page's confirmDelete handler.
vi.mock('../../components/admin/ConfirmDialog', () => ({
  ConfirmDialog: (props: {
    open: boolean;
    title: string;
    message: string;
    confirmLabel?: string;
    onConfirm: () => void;
    onClose: () => void;
  }) =>
    props.open ? (
      <div role="dialog" aria-label="confirm-delete">
        <p>{props.message}</p>
        <button type="button" onClick={props.onConfirm}>
          {props.confirmLabel}
        </button>
        <button type="button" onClick={props.onClose}>
          cancel-confirm
        </button>
      </div>
    ) : null,
}));

beforeEach(() => {
  teamsRef.current = { data: LIST, isLoading: false, isFetching: false, isError: false };
  deleteMutate.mockReset().mockResolvedValue(undefined);
  navigateSpy.mockReset();
});

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter>{children}</MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

const renderPage = () => render(<TeamsPage />, { wrapper });

const openRowMenu = async (teamName: string) => {
  fireEvent.click(screen.getByRole('button', { name: `Actions for ${teamName}` }));
  return screen.findByRole('menu');
};

describe('TeamsPage', () => {
  it('renders a row per team with description, member count and status', () => {
    renderPage();

    expect(screen.getByText('Alpha')).toBeTruthy();
    expect(screen.getByText('First team')).toBeTruthy();
    expect(screen.getByText('Beta')).toBeTruthy();
    // Alpha is active, Beta is disabled.
    expect(screen.getByText('Active')).toBeTruthy();
    expect(screen.getByText('Disabled')).toBeTruthy();
    // Beta has no description → em dash placeholder.
    expect(screen.getByText('—')).toBeTruthy();
  });

  it('shows the loading hint while the first page is loading', () => {
    setTeams({ data: undefined, isLoading: true });
    renderPage();

    expect(screen.getByText('Loading…')).toBeTruthy();
  });

  it('shows a background-fetch progress bar', () => {
    setTeams({ isFetching: true });
    renderPage();

    expect(screen.getByRole('progressbar')).toBeTruthy();
  });

  it('renders an error alert instead of the table when the list fails', () => {
    setTeams({ data: undefined, isError: true });
    renderPage();

    expect(screen.getByText('The team list could not be loaded.')).toBeTruthy();
    // The table header is not rendered in the error branch.
    expect(screen.queryByText('Members')).toBeNull();
  });

  it('shows the empty-state row when there are no teams', () => {
    setTeams({ data: { items: [], total: 0, page: 0, size: 20 } });
    renderPage();

    expect(screen.getByText('No teams found.')).toBeTruthy();
  });

  it('opens the create dialog in create mode', () => {
    renderPage();

    expect(screen.queryByTestId('team-form-dialog')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Create team' }));

    expect(screen.getByTestId('team-form-dialog')).toBeTruthy();
    expect(screen.getByText('form-mode:create')).toBeTruthy();
  });

  it('closes the create dialog via its onClose', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Create team' }));
    fireEvent.click(screen.getByRole('button', { name: 'close-form' }));

    expect(screen.queryByTestId('team-form-dialog')).toBeNull();
  });

  it('opens the edit dialog seeded with the chosen team', async () => {
    renderPage();

    await openRowMenu('Alpha');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));

    expect(screen.getByText('form-mode:edit')).toBeTruthy();
    expect(screen.getByText('form-team:Alpha')).toBeTruthy();
  });

  it('navigates to a team detail page when its row is clicked', () => {
    renderPage();

    fireEvent.click(screen.getByText('Alpha'));

    expect(navigateSpy).toHaveBeenCalledWith('/admin/teams/team-1');
  });

  it('deletes a team and surfaces a success toast', async () => {
    renderPage();

    await openRowMenu('Alpha');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }));

    // The confirmation names the team being deleted.
    expect(screen.getByText(/Delete .*Alpha.* and all its memberships/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(deleteMutate).toHaveBeenCalledWith('team-1'));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Alpha');
    expect(alert.textContent).toContain('deleted');
    expect(alert.className).toContain('MuiAlert-colorSuccess');
  });

  it('surfaces an error toast when the delete fails', async () => {
    deleteMutate.mockRejectedValueOnce(new Error('boom'));
    renderPage();

    await openRowMenu('Beta');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }));
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(deleteMutate).toHaveBeenCalledWith('team-2'));
    const alert = await screen.findByText('Could not delete the team.');
    expect(alert.closest('.MuiAlert-colorError')).toBeTruthy();
  });

  it('dismisses the delete confirmation without calling the mutation', async () => {
    renderPage();

    await openRowMenu('Alpha');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }));
    fireEvent.click(screen.getByRole('button', { name: 'cancel-confirm' }));

    expect(screen.queryByRole('dialog', { name: 'confirm-delete' })).toBeNull();
    expect(deleteMutate).not.toHaveBeenCalled();
  });

  it('reveals a clear button while searching and resets the field', () => {
    renderPage();

    const input = screen.getByPlaceholderText('Search by name or description') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'alpha' } });
    expect(input.value).toBe('alpha');

    fireEvent.click(screen.getByRole('button', { name: 'Clear search' }));
    expect(input.value).toBe('');
  });
});
