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

import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../../theme/theme';
import { TeamFormDialog } from './TeamFormDialog';

const createMutateAsync = vi.fn();
vi.mock('../../../api/hooks/useTeams', () => ({
  useCreateTeam: () => ({ mutateAsync: createMutateAsync, isPending: false }),
  useUpdateTeam: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));
vi.mock('../../../api/hooks/useTeamAvatar', () => ({
  useUploadTeamAvatar: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useRemoveTeamAvatar: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));
vi.mock('../../../api/hooks/useReviews', () => ({
  usePrincipalSearch: vi.fn().mockReturnValue({
    data: {
      principals: [
        { id: 'u-lena', kind: 'USER', displayName: 'Lena Lead', avatarUrl: null },
        { id: 't-alpha', kind: 'TEAM', displayName: 'Alpha' },
      ],
    },
    isFetching: false,
  }),
}));

function renderCreate() {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <TeamFormDialog open mode="create" team={undefined} onClose={vi.fn()} />
    </ThemeProvider>,
  );
}

describe('TeamFormDialog — create mode (#586 follow-up)', () => {
  beforeEach(() => createMutateAsync.mockReset());

  it('requires a team lead — submit without one never calls the mutation', async () => {
    renderCreate();
    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'Core' } });

    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    expect(await screen.findByText('Every team needs a lead — pick one.')).toBeInTheDocument();
    expect(createMutateAsync).not.toHaveBeenCalled();
  });

  it('offers users (not teams) with their avatar and submits lead + flags', async () => {
    createMutateAsync.mockResolvedValue({ id: 't-new' });
    renderCreate();
    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'Core' } });

    // The visibility switches are configurable at creation now.
    fireEvent.click(screen.getByLabelText('Show members on the public profile'));

    const leadInput = screen.getByLabelText(/Team lead/);
    fireEvent.mouseDown(leadInput);
    fireEvent.change(leadInput, { target: { value: 'Le' } });
    const option = await screen.findByText('Lena Lead');
    // Users only — the team principal from the directory is filtered out.
    expect(screen.queryByText('Alpha')).not.toBeInTheDocument();
    // The option row leads with the person's avatar (initials fallback "LL").
    expect(option.parentElement).toHaveTextContent('LL');
    fireEvent.click(option);

    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() =>
      expect(createMutateAsync).toHaveBeenCalledWith({
        name: 'Core',
        description: undefined,
        leadUserId: 'u-lena',
        enabled: true,
        profileShowMembers: true,
        profileShowReviews: false,
      }),
    );
  });
});
