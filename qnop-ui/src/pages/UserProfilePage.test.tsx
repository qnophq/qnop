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
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import type { AxiosResponse } from 'axios';
import type { PublicUserProfile } from '../api/generated';
import { usersApi } from '../api/config';
import { buildTheme } from '../theme/theme';
import { useAuthStore } from '../stores/authStore';
import { UserProfilePage } from './UserProfilePage';

vi.mock('../api/config', () => ({
  usersApi: {
    getUserProfile: vi.fn(),
    getUserProfileBySlug: vi.fn(),
  },
}));

const ANNA_ID = '123e4567-e89b-12d3-a456-426614174000';
const SELF_ID = '999e4567-e89b-12d3-a456-426614174999';

function profile(overrides: Partial<PublicUserProfile> = {}): PublicUserProfile {
  return {
    id: ANNA_ID,
    displayName: 'Anna Krause',
    slug: 'anna-krause',
    createdAt: '2026-01-05T09:10:00Z',
    stats: {
      reviewsOwned: 1,
      reviewsParticipating: 2,
      annotationsRaised: 3,
      annotationsResolved: 1,
      commentsWritten: 4,
    },
    teams: [],
    ...overrides,
  };
}

function respond(data: PublicUserProfile) {
  return Promise.resolve({ data } as AxiosResponse<PublicUserProfile>);
}

function LocationProbe() {
  return <div data-testid="location">{useLocation().pathname}</div>;
}

function renderPage(segment: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/users/${segment}`]}>
          <Routes>
            <Route
              path="/users/:userId"
              element={
                <>
                  <UserProfilePage />
                  <LocationProbe />
                </>
              }
            />
            <Route path="/profile" element={<div data-testid="own-profile" />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.setState({ userId: SELF_ID });
});

describe('UserProfilePage slug URLs (issue #486)', () => {
  it('resolves a slug segment via the by-slug endpoint and keeps the pretty URL', async () => {
    vi.mocked(usersApi.getUserProfileBySlug).mockReturnValue(respond(profile()) as never);

    renderPage('anna-krause');

    expect(await screen.findByText('Anna Krause')).toBeInTheDocument();
    expect(usersApi.getUserProfileBySlug).toHaveBeenCalledWith({ slug: 'anna-krause' });
    expect(usersApi.getUserProfile).not.toHaveBeenCalled();
    expect(screen.getByTestId('location')).toHaveTextContent('/users/anna-krause');
  });

  it('canonicalises a UUID visit to the slug URL without refetching', async () => {
    vi.mocked(usersApi.getUserProfile).mockReturnValue(respond(profile()) as never);

    renderPage(ANNA_ID);

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/users/anna-krause'),
    );
    expect(screen.getByText('Anna Krause')).toBeInTheDocument();
    expect(usersApi.getUserProfile).toHaveBeenCalledWith({ userId: ANNA_ID });
    // The slug cache entry was seeded before the replace — no second fetch.
    expect(usersApi.getUserProfileBySlug).not.toHaveBeenCalled();
  });

  it('stays on the UUID URL for profiles without a slug', async () => {
    vi.mocked(usersApi.getUserProfile).mockReturnValue(
      respond(profile({ slug: undefined })) as never,
    );

    renderPage(ANNA_ID);

    expect(await screen.findByText('Anna Krause')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent(`/users/${ANNA_ID}`);
  });

  it('redirects the own slug to /profile once the payload identifies the viewer', async () => {
    vi.mocked(usersApi.getUserProfileBySlug).mockReturnValue(
      respond(profile({ id: SELF_ID, displayName: 'Me Myself', slug: 'me-myself' })) as never,
    );

    renderPage('me-myself');

    expect(await screen.findByTestId('own-profile')).toBeInTheDocument();
  });

  it('redirects the own id to /profile without fetching', () => {
    renderPage(SELF_ID);

    expect(screen.getByTestId('own-profile')).toBeInTheDocument();
    expect(usersApi.getUserProfile).not.toHaveBeenCalled();
    expect(usersApi.getUserProfileBySlug).not.toHaveBeenCalled();
  });
});
