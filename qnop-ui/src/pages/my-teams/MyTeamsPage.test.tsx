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
      { teamId: 't1', name: 'Platform', slug: 'platform', teamRole: 'LEAD', memberCount: 7 },
      {
        teamId: 't2',
        name: 'Design Guild',
        slug: 'design-guild',
        teamRole: 'MEMBER',
        memberCount: 3,
      },
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
    expect(manage.getAttribute('href')).toBe('/my-teams/platform');
  });

  it('links member-only teams to a view-only roster (not a management action)', () => {
    renderPage();

    expect(screen.getByText("Team you're in")).toBeTruthy();
    const view = screen.getByRole('link', { name: 'View Design Guild' });
    expect(view.getAttribute('href')).toBe('/my-teams/design-guild');
    expect(screen.queryByRole('link', { name: 'Manage Design Guild' })).toBeNull();
  });

  it('shows an empty hint when the caller is in no team', () => {
    myTeamsState.data = { items: [] };
    renderPage();

    expect(screen.getByText('You’re not in any team yet.')).toBeTruthy();
  });

  it('surfaces a leadership rank and headline stats for the teams led', () => {
    renderPage();

    expect(screen.getByText('Team Lead')).toBeTruthy();
    expect(screen.getByText('Teams led')).toBeTruthy();
    expect(screen.getByText('Largest team')).toBeTruthy();
  });

  it("shows each led team's roster tier and an unlockable achievement", () => {
    renderPage();

    // 7 members → the Crew tier.
    expect(screen.getByText('Crew')).toBeTruthy();
    // The first-team achievement is earned once any team is led.
    expect(screen.getByText('First team')).toBeTruthy();
  });

  it('hides the leadership banner entirely when no team is led', () => {
    myTeamsState.data = {
      items: [{ teamId: 't2', name: 'Design Guild', teamRole: 'MEMBER', memberCount: 3 }],
    };
    renderPage();

    expect(screen.queryByText('Leadership HQ')).toBeNull();
  });
});
