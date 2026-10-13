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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter } from 'react-router';
import type { MyTeamListResponse } from '../../api/generated';
import { applyLocale } from '../../i18n';
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

function renderPage() {
  return render(
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <MemoryRouter>
        <MyTeamsPage />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

/** My Teams in German (issue #836): plurals, the interpolated hero line and the model's keys. */
describe('MyTeamsPage in German', () => {
  beforeEach(async () => {
    myTeamsState.data = {
      items: [
        { teamId: 't1', name: 'Platform', slug: 'platform', teamRole: 'LEAD', memberCount: 7 },
        { teamId: 't2', name: 'Design', slug: 'design', teamRole: 'MEMBER', memberCount: 1 },
        { teamId: 't3', name: 'Legal', slug: 'legal', teamRole: 'MEMBER', memberCount: 2 },
      ],
    };
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the leadership hero with its rank and pluralised summary', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Meine Teams' })).toBeInTheDocument();
    // The hero rank and the LEAD role badge on the Platform card (admin:roles.team.LEAD).
    expect(screen.getAllByText('Teamleitung')).toHaveLength(2);
    expect(screen.getByText('Du leitest 1 Team · insgesamt 7 Teammitglieder')).toBeInTheDocument();
  });

  it('pluralises the section labels and member counts', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Team, das du leitest' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Teams, in denen du bist' })).toBeInTheDocument();
    expect(screen.getByText('7 Mitglieder')).toBeInTheDocument();
    expect(screen.getByText('1 Mitglied')).toBeInTheDocument();
  });

  it('labels the cards and their tier progress in German', () => {
    renderPage();

    expect(screen.getByRole('link', { name: 'Platform verwalten' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Design ansehen' })).toBeInTheDocument();
    expect(screen.getByText('Noch 3 bis Gilde')).toBeInTheDocument();
    expect(
      screen.getByRole('progressbar', { name: 'Fortschritt der Stufe Crew' }),
    ).toBeInTheDocument();
  });
});
