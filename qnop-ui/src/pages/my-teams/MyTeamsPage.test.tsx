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
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter } from 'react-router-dom';
import type { MyTeamListResponse } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { MyTeamsPage } from './MyTeamsPage';

const { myTeamsState } = vi.hoisted(() => ({
  myTeamsState: { data: undefined, isLoading: false, isError: false } as {
    data: MyTeamListResponse | undefined;
    isLoading: boolean;
    isError: boolean;
  },
}));

vi.mock('../../api/hooks/useMyTeams', () => ({
  useMyTeams: () => myTeamsState,
}));

beforeEach(() => {
  myTeamsState.data = {
    items: [
      { teamId: 't1', name: 'Platform', teamRole: 'LEAD' },
      { teamId: 't2', name: 'Design Guild', teamRole: 'MEMBER' },
    ],
  };
  myTeamsState.isLoading = false;
  myTeamsState.isError = false;
});

function renderPage() {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter>
        <MyTeamsPage />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

describe('MyTeamsPage', () => {
  it('renders led teams as management cards that link to their detail page', () => {
    renderPage();

    const manage = screen.getByRole('link', { name: 'Manage Platform' });
    expect(manage.getAttribute('href')).toBe('/my-teams/t1');
  });

  it('lists member-only teams informationally, without a management link', () => {
    renderPage();

    expect(screen.getByText('Also a member of')).toBeTruthy();
    expect(screen.getByText('Design Guild')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Manage Design Guild' })).toBeNull();
  });

  it('shows an empty hint when the caller leads no team', () => {
    myTeamsState.data = { items: [{ teamId: 't2', name: 'Design Guild', teamRole: 'MEMBER' }] };
    renderPage();

    expect(screen.getByText('You don’t lead any team yet.')).toBeTruthy();
  });
});
