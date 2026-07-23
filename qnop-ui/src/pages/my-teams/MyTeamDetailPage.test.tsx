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
import type { TeamDetail, TeamMember } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { useAuthStore } from '../../stores/authStore';
import { MyTeamDetailPage } from './MyTeamDetailPage';

const { teamState, setRoleMutate, removeMemberMutate } = vi.hoisted(() => ({
  teamState: { data: undefined, isLoading: false, isError: false } as {
    data: TeamDetail | undefined;
    isLoading: boolean;
    isError: boolean;
  },
  setRoleMutate: vi.fn(),
  removeMemberMutate: vi.fn(),
}));

vi.mock('../../api/hooks/useMyTeams', () => ({
  useMyTeam: () => teamState,
  useSetMyTeamMemberRole: () => ({ mutate: setRoleMutate }),
  useRemoveMyTeamMember: () => ({ mutate: removeMemberMutate }),
}));

vi.mock('../../components/my-teams/EditMyTeamDialog', () => ({
  EditMyTeamDialog: (props: { open: boolean; team: TeamDetail; onClose: () => void }) =>
    props.open ? (
      <div data-testid="edit-team-dialog">
        <span data-testid="edit-team-name">{props.team.name}</span>
        <button type="button" onClick={props.onClose}>
          close-edit
        </button>
      </div>
    ) : null,
}));

vi.mock('../../components/my-teams/AddMyTeamMemberDialog', () => ({
  AddMyTeamMemberDialog: (props: {
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

const LEAD: TeamMember = {
  userId: 'u1',
  displayName: 'Ada Lovelace',
  slug: 'ada-lovelace',
  avatarUrl: undefined,
  email: 'ada@example.com',
  teamRole: 'LEAD',
  joinedAt: '2026-01-01T10:00:00Z',
};

const MEMBER: TeamMember = {
  userId: 'u2',
  displayName: 'Alan Turing',
  slug: 'alan-turing',
  avatarUrl: undefined,
  email: 'alan@example.com',
  teamRole: 'MEMBER',
  joinedAt: '2026-02-01T10:00:00Z',
};

function makeTeam(overrides: Partial<TeamDetail> = {}): TeamDetail {
  return {
    id: 't1',
    name: 'Platform',
    slug: 'platform',
    description: 'Owns the ingest pipeline.',
    viewerRole: 'LEAD',
    viewerCanManage: true,
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
  useAuthStore.setState({ userId: null, role: null });
});

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>
        {/* Enter by the pretty slug; the page must key its mutations off the
            canonical team.id ('t1'), not the URL segment. */}
        <MemoryRouter initialEntries={['/my-teams/platform']}>
          <Routes>
            <Route path="/my-teams/:id" element={<MyTeamDetailPage />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('MyTeamDetailPage', () => {
  it('renders an error alert linking back to My Teams on failure', () => {
    teamState.data = undefined;
    teamState.isError = true;
    renderPage();

    const back = screen.getByRole('link', { name: 'Back to my teams' });
    expect(back.getAttribute('href')).toBe('/my-teams');
  });

  it('renders the header and every member with a link to their profile', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Platform' })).toBeTruthy();
    // Members render as profile links (avatar + name → the profile page).
    expect(
      screen.getByRole('link', { name: "View Ada Lovelace's profile" }).getAttribute('href'),
    ).toBe('/users/ada-lovelace');
    expect(
      screen.getByRole('link', { name: "View Alan Turing's profile" }).getAttribute('href'),
    ).toBe('/users/alan-turing');
  });

  it('promotes a member to lead through the non-admin client', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Alan Turing' }));
    fireEvent.click(await screen.findByText('Make lead'));

    expect(setRoleMutate.mock.calls[0][0]).toEqual({
      teamId: 't1',
      userId: 'u2',
      teamRole: 'LEAD',
    });
  });

  it('removes a member after confirming, through the non-admin client', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Ada Lovelace' }));
    fireEvent.click(await screen.findByText('Remove from team'));
    expect(await screen.findByText('Remove Ada Lovelace from this team?')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));

    expect(removeMemberMutate.mock.calls[0][0]).toEqual({ teamId: 't1', userId: 'u1' });
  });

  it('offers no actions at all on the caller’s own row (#542 follow-up)', () => {
    useAuthStore.setState({ userId: 'u1' }); // the caller is the lead Ada (u1)
    renderPage();

    // Demoting yourself is another lead's or an admin's call, and self-removal
    // was already off the table — so the own row carries no actions menu at all.
    expect(screen.queryByRole('button', { name: 'Actions for Ada Lovelace' })).toBeNull();
    // Other rows keep their menu.
    expect(screen.getByRole('button', { name: 'Actions for Alan Turing' })).toBeTruthy();
  });

  it('hides the own-row actions for admins too — one’s own role changes in the admin console', () => {
    useAuthStore.setState({ userId: 'u1', role: 'ADMIN' });
    renderPage();

    expect(screen.queryByRole('button', { name: 'Actions for Ada Lovelace' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Actions for Alan Turing' })).toBeTruthy();
  });

  it('surfaces an error toast when a removal is rejected (last-lead guard)', async () => {
    removeMemberMutate.mockImplementation(
      (_vars: unknown, opts?: { onError?: (e: unknown) => void }) =>
        opts?.onError?.(new Error('rejected')),
    );
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Ada Lovelace' }));
    fireEvent.click(await screen.findByText('Remove from team'));
    fireEvent.click(await screen.findByRole('button', { name: 'Remove' }));

    expect(await screen.findByText('Could not remove the member.')).toBeTruthy();
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

  it('lets a lead edit the team presentation — avatar + description (#509)', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Edit team' }));

    expect(await screen.findByTestId('edit-team-dialog')).toBeTruthy();
    expect(screen.getByTestId('edit-team-name').textContent).toBe('Platform');

    fireEvent.click(screen.getByRole('button', { name: 'close-edit' }));
    await waitFor(() => expect(screen.queryByTestId('edit-team-dialog')).toBeNull());
  });

  it('renders a read-only roster with no management affordances for a plain member', () => {
    teamState.data = makeTeam({ viewerRole: 'MEMBER', viewerCanManage: false });
    renderPage();

    // The members are still listed and linkable...
    expect(screen.getByRole('link', { name: "View Ada Lovelace's profile" })).toBeTruthy();
    // ...but nothing can be managed.
    expect(screen.queryByRole('button', { name: 'Add member' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit team' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Actions for Ada Lovelace' })).toBeNull();
    expect(screen.queryByRole('columnheader', { name: 'Actions' })).toBeNull();
  });
});
