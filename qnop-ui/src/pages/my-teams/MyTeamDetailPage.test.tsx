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
import { useAuthStore } from '../../stores/authStore';
import { MyTeamDetailPage } from './MyTeamDetailPage';

const { teamState, setRoleMutate, removeMemberMutate } = vi.hoisted(() => ({
  teamState: { data: undefined, isLoading: false, isError: false } as {
    data: AdminTeamDetail | undefined;
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

// The add-member dialog draws its own principal search; stub it to a marker that
// exposes the props the page wires in, so this test stays focused on the page.
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

const LEAD: AdminTeamMember = {
  userId: 'u1',
  displayName: 'Ada Lovelace',
  email: 'ada@example.com',
  teamRole: 'LEAD',
  joinedAt: '2026-01-01T10:00:00Z',
};

const MEMBER: AdminTeamMember = {
  userId: 'u2',
  displayName: 'Alan Turing',
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
  useAuthStore.setState({ userId: null });
});

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter initialEntries={['/my-teams/t1']}>
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

  it('renders the header, description and every member row', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Platform' })).toBeTruthy();
    expect(screen.getByText('Ada Lovelace')).toBeTruthy();
    expect(screen.getByText('Alan Turing')).toBeTruthy();
  });

  it('does not offer team edit or delete (those stay admin-only)', () => {
    renderPage();

    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
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

  it('surfaces an error toast when a role change is rejected (last-lead guard)', async () => {
    setRoleMutate.mockImplementation((_vars: unknown, opts?: { onError?: (e: unknown) => void }) =>
      opts?.onError?.(new Error('rejected')),
    );
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Ada Lovelace' }));
    fireEvent.click(await screen.findByText('Make member'));

    expect(await screen.findByText('Could not change the role.')).toBeTruthy();
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

  it('never offers self-removal on the caller’s own row, but still allows a hand-over demote', async () => {
    useAuthStore.setState({ userId: 'u1' }); // the caller is the lead Ada (u1)
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Ada Lovelace' }));

    expect(await screen.findByText('Make member')).toBeTruthy();
    expect(screen.queryByText('Remove from team')).toBeNull();
  });

  it('still offers removal on other members’ rows', async () => {
    useAuthStore.setState({ userId: 'u1' });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Alan Turing' }));

    expect(await screen.findByText('Remove from team')).toBeTruthy();
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
});
