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

import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import type { PublicTeamProfile } from '../api/generated';
import { teamsApi } from '../api/config';
import { buildTheme } from '../theme/theme';
import { TeamProfilePage } from './TeamProfilePage';

vi.mock('../api/config', () => ({
  teamsApi: { getTeamProfile: vi.fn(), getTeamProfileBySlug: vi.fn() },
}));

const respond = (data: unknown) => Promise.resolve({ data });

function profile(overrides: Partial<PublicTeamProfile> = {}): PublicTeamProfile {
  return {
    id: 'b0000000-0000-0000-0000-000000000001',
    name: 'Alpha',
    slug: 'alpha',
    description: 'Primary review team',
    viewerIsMember: false,
    createdAt: '2026-01-02T08:00:00Z',
    ...overrides,
  };
}

function renderPage(path = '/teams/alpha') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <ThemeProvider theme={buildTheme('light')}>
          <Routes>
            <Route path="/teams/:teamId" element={<TeamProfilePage />} />
          </Routes>
        </ThemeProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TeamProfilePage (#586)', () => {
  it('renders identity always, and quiet lock notes for the hidden sections', async () => {
    vi.mocked(teamsApi.getTeamProfileBySlug).mockReturnValue(respond(profile()) as never);
    renderPage();

    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.getByText('Primary review team')).toBeInTheDocument();
    expect(screen.getByText(/Team since/)).toBeInTheDocument();
    // Hidden sections read as deliberate privacy, not as emptiness.
    expect(screen.getByText('This team keeps its roster private.')).toBeInTheDocument();
    expect(screen.getByText('This team keeps its review activity private.')).toBeInTheDocument();
    expect(screen.queryByTestId('team-profile-roster')).not.toBeInTheDocument();
    expect(vi.mocked(teamsApi.getTeamProfileBySlug)).toHaveBeenCalledWith({ slug: 'alpha' });
  });

  it('renders roster, missions and the scoreboard when the sections are visible', async () => {
    vi.mocked(teamsApi.getTeamProfileBySlug).mockReturnValue(
      respond(
        profile({
          viewerIsMember: true,
          members: [
            {
              id: 'a0000000-0000-0000-0000-000000000001',
              displayName: 'Ada Admin',
              slug: 'ada-admin',
              role: 'LEAD',
            },
            {
              id: 'a0000000-0000-0000-0000-000000000002',
              displayName: 'Max Member',
              slug: 'max-member',
              role: 'MEMBER',
            },
          ],
          reviews: [
            {
              id: 'e0000000-0000-0000-0000-000000000001',
              title: 'Supplier contract',
              slug: 'supplier-contract',
              contentType: 'application/pdf',
              workflowState: 'IN_REVIEW',
              updatedAt: '2026-07-20T10:00:00Z',
            },
            {
              id: 'e0000000-0000-0000-0000-000000000002',
              title: 'NDA template',
              workflowState: 'FINALIZED',
              updatedAt: '2026-07-01T10:00:00Z',
            },
          ],
        }),
      ) as never,
    );
    renderPage();

    expect(await screen.findByTestId('team-profile-roster')).toBeInTheDocument();
    expect(screen.getByText('Ada Admin')).toBeInTheDocument();
    expect(screen.getByLabelText('Team lead')).toBeInTheDocument();
    // Each mission leads with the document-type sheet, as on /reviews.
    expect(screen.getAllByTestId('document-icon').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByRole('img', { name: 'PDF document' })).toBeInTheDocument();
    // Review missions link to the review (slug preferred, id fallback).
    const mission = screen.getByText('Supplier contract').closest('a');
    expect(mission).toHaveAttribute('href', '/reviews/supplier-contract');
    expect(screen.getByText('NDA template').closest('a')).toHaveAttribute(
      'href',
      '/reviews/e0000000-0000-0000-0000-000000000002',
    );
    // The scoreboard: members, active (IN_REVIEW), completed (FINALIZED).
    expect(screen.getByText('Members')).toBeInTheDocument();
    expect(screen.getByText('Active reviews')).toBeInTheDocument();
    expect(screen.getByText('Completed')).toBeInTheDocument();
    // Your-team chip links into My Teams.
    expect(screen.getByText('Your team').closest('a')).toHaveAttribute('href', '/my-teams/alpha');
  });

  it('shows the not-found message on the anti-enumeration 404', async () => {
    vi.mocked(teamsApi.getTeamProfileBySlug).mockReturnValue(
      Promise.reject(new Error('404')) as never,
    );
    renderPage('/teams/ghost-team');

    expect(await screen.findByText('This team does not exist.')).toBeInTheDocument();
  });
});
