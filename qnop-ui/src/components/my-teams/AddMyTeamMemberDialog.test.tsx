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
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import type { PrincipalView } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { AddMyTeamMemberDialog } from './AddMyTeamMemberDialog';

const { addMutateAsync } = vi.hoisted(() => ({ addMutateAsync: vi.fn() }));

const CANDIDATES = [
  { kind: 'USER', id: 'u7', displayName: 'Grace Hopper' },
] as unknown as PrincipalView[];

vi.mock('../../api/hooks/useReviews', () => ({
  usePrincipalSearch: () => ({ data: { principals: CANDIDATES }, isFetching: false }),
}));
vi.mock('../../api/hooks/useMyTeams', () => ({
  useAddMyTeamMember: () => ({ mutateAsync: addMutateAsync }),
}));

function renderDialog(canGrantLead: boolean) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <AddMyTeamMemberDialog
        open
        teamId="t1"
        existingMemberIds={[]}
        canGrantLead={canGrantLead}
        onClose={vi.fn()}
      />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  addMutateAsync.mockReset();
  addMutateAsync.mockResolvedValue({});
});

describe('AddMyTeamMemberDialog (#936)', () => {
  it('is an invite dialog for a lead: no role choice, always MEMBER', async () => {
    const user = userEvent.setup();
    renderDialog(false);

    expect(screen.getByRole('heading', { name: 'Invite members' })).toBeTruthy();
    expect(screen.queryByRole('combobox', { name: /Team role/i })).toBeNull();
    expect(screen.getByText(/Only an administrator can make someone a lead/)).toBeTruthy();

    await user.click(screen.getByRole('combobox', { name: /Users/i }));
    await user.click(await screen.findByRole('option', { name: /Grace Hopper/ }));
    await user.click(screen.getByRole('button', { name: 'Send invitation' }));

    expect(addMutateAsync).toHaveBeenCalledWith({
      teamId: 't1',
      userId: 'u7',
      teamRole: 'MEMBER',
    });
  });

  it('keeps the role choice for an admin', () => {
    renderDialog(true);

    expect(screen.getByRole('heading', { name: 'Add members' })).toBeTruthy();
    expect(screen.getByRole('combobox', { name: /Team role/i })).toBeTruthy();
  });
});
