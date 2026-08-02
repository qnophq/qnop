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
import type { AdminUserSummary } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { AddMemberDialog } from './AddMemberDialog';

const CANDIDATES: AdminUserSummary[] = ['Ada', 'Ben', 'Cleo', 'Dara'].map((name, i) => ({
  id: `user-${i}`,
  displayName: name,
  username: name.toLowerCase(),
  email: `${name.toLowerCase()}@example.com`,
  role: 'MEMBER' as AdminUserSummary['role'],
  source: 'INTERNAL' as AdminUserSummary['source'],
  enabled: true,
}));

vi.mock('../../../api/hooks/useAdminUsers', () => ({
  useAdminUsers: () => ({ data: { items: CANDIDATES, total: 4, page: 0, size: 10 } }),
}));
vi.mock('../../../api/hooks/useTeams', () => ({
  useAddTeamMember: () => ({ mutateAsync: vi.fn().mockResolvedValue(undefined) }),
}));

function renderDialog(remainingSlots?: number) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <AddMemberDialog
        open
        teamId="team-1"
        existingMemberIds={[]}
        remainingSlots={remainingSlots}
        onClose={vi.fn()}
      />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('AddMemberDialog — per-team ceiling (#691)', () => {
  it('will not let more people be picked than the team has room for', async () => {
    // The additions go out in parallel, so a selection that overshoots used to be
    // accepted in full: a team at 3 of 5 took four people and ended at 7.
    const user = userEvent.setup();
    renderDialog(2);

    // By label: the role select is a combobox too.
    // The list stays open across picks (disableCloseOnSelect), so one opening
    // is enough for all of them.
    await user.click(screen.getByRole('combobox', { name: /Users/i }));
    await user.click(await screen.findByRole('option', { name: /Ada/ }));
    await user.click(await screen.findByRole('option', { name: /Ben/ }));

    // Two picked, two slots — the rest of the list can no longer be chosen.
    expect(await screen.findByRole('option', { name: /Cleo/ })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  it('says how much room is left', () => {
    renderDialog(2);

    expect(screen.getByText('2 more members fit in this team.')).toBeInTheDocument();
  });

  it('stays unbounded where the deployment sets no ceiling', () => {
    renderDialog(undefined);

    expect(screen.queryByText(/fit in this team/)).not.toBeInTheDocument();
  });
});
