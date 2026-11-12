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
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { MyTeamInvitation } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { MyTeamInvitations } from './MyTeamInvitations';

const { state, acceptMutate, declineMutate } = vi.hoisted(() => ({
  state: { items: [] as MyTeamInvitation[] },
  acceptMutate: vi.fn(),
  declineMutate: vi.fn(),
}));

vi.mock('../../api/hooks/useMyTeams', () => ({
  useMyTeamInvitations: () => ({ data: { items: state.items } }),
  useAcceptTeamInvitation: () => ({ mutate: acceptMutate, isPending: false, error: null }),
  useDeclineTeamInvitation: () => ({ mutate: declineMutate, isPending: false, error: null }),
}));

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MyTeamInvitations />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  acceptMutate.mockReset();
  declineMutate.mockReset();
  state.items = [
    {
      teamId: 't1',
      teamName: 'Platform',
      invitedByName: 'Ada Lovelace',
      invitedAt: '2026-11-01T10:00:00Z',
    },
    { teamId: 't2', teamName: 'Legal', invitedAt: '2026-11-02T10:00:00Z' },
  ];
});

describe('MyTeamInvitations (#936)', () => {
  it('renders nothing when there are no pending invitations', () => {
    state.items = [];
    const { container } = renderPanel();
    expect(container.textContent).toBe('');
  });

  it('lists each invitation with who sent it', () => {
    renderPanel();

    expect(screen.getByRole('heading', { name: '2 team invitations' })).toBeTruthy();
    expect(screen.getByText('Platform')).toBeTruthy();
    expect(screen.getByText(/Invited by Ada Lovelace/)).toBeTruthy();
    // An inviter whose account is gone reads without a name.
    expect(screen.getByText('Legal')).toBeTruthy();
  });

  it('accepts and declines per team', () => {
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Accept the invitation to Platform' }));
    expect(acceptMutate).toHaveBeenCalledWith('t1');

    fireEvent.click(screen.getByRole('button', { name: 'Decline the invitation to Legal' }));
    expect(declineMutate).toHaveBeenCalledWith('t2');
  });
});
