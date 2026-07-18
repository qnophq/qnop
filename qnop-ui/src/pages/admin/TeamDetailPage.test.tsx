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
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { AdminTeamDetail, AdminTeamMember } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { TeamDetailPage } from './TeamDetailPage';

const { teamState, setRoleMutate, removeMemberMutate } = vi.hoisted(() => ({
  teamState: { data: undefined, isLoading: false, isError: false } as {
    data: AdminTeamDetail | undefined;
    isLoading: boolean;
    isError: boolean;
  },
  setRoleMutate: vi.fn(),
  removeMemberMutate: vi.fn(),
}));

vi.mock('../../api/hooks/useTeams', () => ({
  useTeam: () => teamState,
  useSetTeamMemberRole: () => ({ mutate: setRoleMutate }),
  useRemoveTeamMember: () => ({ mutate: removeMemberMutate }),
}));

// The heavy member/team dialogs pull in their own api hooks and search UI;
// stub them down to markers that expose the props the page wires in.
vi.mock('../../components/admin/teams/AddMemberDialog', () => ({
  AddMemberDialog: (props: {
    open: boolean;
    teamId: string;
    existingMemberIds: string[];
    onClose: () => void;
  }) =>
    props.open ? (
      <div data-testid="add-member-dialog">
        <span data-testid="add-team-id">{props.teamId}</span>
        <span data-testid="add-existing">{props.existingMemberIds.join(',')}</span>
        <button type="button" onClick={props.onClose}>
          close-add
        </button>
      </div>
    ) : null,
}));

vi.mock('../../components/admin/teams/TeamFormDialog', () => ({
  TeamFormDialog: (props: {
    open: boolean;
    mode: string;
    team?: { name: string };
    onClose: () => void;
  }) =>
    props.open ? (
      <div data-testid="team-form-dialog">
        <span data-testid="edit-mode">{props.mode}</span>
        <span data-testid="edit-name">{props.team?.name}</span>
        <button type="button" onClick={props.onClose}>
          close-edit
        </button>
      </div>
    ) : null,
}));

const LEAD: AdminTeamMember = {
  userId: 'u1',
  displayName: 'Ada Lovelace',
  slug: 'ada-lovelace',
  avatarUrl: undefined,
  email: 'ada@example.com',
  teamRole: 'LEAD',
  joinedAt: '2026-01-01T10:00:00Z',
};

const MEMBER: AdminTeamMember = {
  userId: 'u2',
  displayName: 'Alan Turing',
  slug: 'alan-turing',
  avatarUrl: undefined,
  email: 'alan@example.com',
  teamRole: 'MEMBER',
  joinedAt: '2026-02-01T10:00:00Z',
};

function makeTeam(overrides: Partial<AdminTeamDetail> = {}): AdminTeamDetail {
  return {
    id: 't1',
    name: 'Platform',
    description: 'Owns the ingest pipeline.',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-03-01T00:00:00Z',
    members: [LEAD, MEMBER],
    ...overrides,
  };
}

beforeEach(() => {
  setRoleMutate.mockReset();
  removeMemberMutate.mockReset();
  teamState.data = makeTeam();
  teamState.isLoading = false;
  teamState.isError = false;
});

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter initialEntries={['/admin/teams/t1']}>
          <Routes>
            <Route path="/admin/teams/:id" element={<TeamDetailPage />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('TeamDetailPage', () => {
  it('shows the loading placeholder while the team query is in flight', () => {
    teamState.data = undefined;
    teamState.isLoading = true;
    renderPage();

    expect(screen.getByText('Loading…')).toBeTruthy();
  });

  it('renders an error alert with a way back to the team list on failure', () => {
    teamState.data = undefined;
    teamState.isError = true;
    renderPage();

    expect(screen.getByText(/This team could not be loaded\./)).toBeTruthy();
    const back = screen.getByRole('link', { name: 'Back to teams' });
    expect(back.getAttribute('href')).toBe('/admin/teams');
  });

  it('renders the header, description and every member row', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Platform' })).toBeTruthy();
    expect(screen.getByText('Owns the ingest pipeline.')).toBeTruthy();
    // Members render as profile links (avatar + name → the profile page).
    expect(
      screen.getByRole('link', { name: "View Ada Lovelace's profile" }).getAttribute('href'),
    ).toBe('/users/ada-lovelace');
    expect(
      screen.getByRole('link', { name: "View Alan Turing's profile" }).getAttribute('href'),
    ).toBe('/users/alan-turing');
  });

  it('renders the empty-state row when the team has no members', () => {
    teamState.data = makeTeam({ members: [] });
    renderPage();

    expect(screen.getByText('No members yet. Add the first one.')).toBeTruthy();
  });

  it('demotes a lead to member through the row menu', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Ada Lovelace' }));
    fireEvent.click(await screen.findByText('Make member'));

    expect(setRoleMutate).toHaveBeenCalledWith({
      teamId: 't1',
      userId: 'u1',
      teamRole: 'MEMBER',
    });
  });

  it('promotes a member to lead through the row menu', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Alan Turing' }));
    fireEvent.click(await screen.findByText('Make lead'));

    expect(setRoleMutate).toHaveBeenCalledWith({
      teamId: 't1',
      userId: 'u2',
      teamRole: 'LEAD',
    });
  });

  it('removes a member after confirming the destructive dialog', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Ada Lovelace' }));
    fireEvent.click(await screen.findByText('Remove from team'));

    expect(await screen.findByText('Remove Ada Lovelace from this team?')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));

    expect(removeMemberMutate).toHaveBeenCalledWith({ teamId: 't1', userId: 'u1' });
  });

  it('does not remove a member when the confirmation is cancelled', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Alan Turing' }));
    fireEvent.click(await screen.findByText('Remove from team'));
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));

    await waitFor(() =>
      expect(screen.queryByText('Remove Alan Turing from this team?')).toBeNull(),
    );
    expect(removeMemberMutate).not.toHaveBeenCalled();
  });

  it('opens the add-member dialog wired with the team id and existing members', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));

    expect(await screen.findByTestId('add-member-dialog')).toBeTruthy();
    expect(screen.getByTestId('add-team-id').textContent).toBe('t1');
    expect(screen.getByTestId('add-existing').textContent).toBe('u1,u2');

    fireEvent.click(screen.getByRole('button', { name: 'close-add' }));
    await waitFor(() => expect(screen.queryByTestId('add-member-dialog')).toBeNull());
  });

  it('opens the edit dialog in edit mode with the current team seeded', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

    expect(await screen.findByTestId('team-form-dialog')).toBeTruthy();
    expect(screen.getByTestId('edit-mode').textContent).toBe('edit');
    expect(screen.getByTestId('edit-name').textContent).toBe('Platform');

    fireEvent.click(screen.getByRole('button', { name: 'close-edit' }));
    await waitFor(() => expect(screen.queryByTestId('team-form-dialog')).toBeNull());
  });

  it('renders a team with no description without crashing', () => {
    teamState.data = makeTeam({ description: '' });
    renderPage();

    expect(screen.getByRole('heading', { name: 'Platform' })).toBeTruthy();
    expect(screen.queryByText('Owns the ingest pipeline.')).toBeNull();
  });
});
