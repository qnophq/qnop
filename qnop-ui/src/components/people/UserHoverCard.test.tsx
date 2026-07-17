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
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import type { AxiosResponse } from 'axios';
import type { PublicUserProfile } from '../../api/generated';
import { usersApi } from '../../api/config';
import { buildTheme } from '../../theme/theme';
import { useAuthStore } from '../../stores/authStore';
import { UserHoverCard } from './UserHoverCard';

vi.mock('../../api/config', () => ({
  usersApi: { getUserProfile: vi.fn(), getUserProfileBySlug: vi.fn() },
}));

const ANNA_ID = '123e4567-e89b-12d3-a456-426614174000';
const SELF_ID = '999e4567-e89b-12d3-a456-426614174999';

function profile(): PublicUserProfile {
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
    teams: [{ id: 't1', name: 'Procurement', role: 'MEMBER' }],
  };
}

function renderCard(userId: string | null, link = false) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <UserHoverCard userId={userId} link={link} profileName="Anna Krause">
            <button type="button">Anna Krause</button>
          </UserHoverCard>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

const trigger = () => screen.getByRole('button', { name: 'Anna Krause' });

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.setState({ userId: SELF_ID });
  vi.mocked(usersApi.getUserProfile).mockReturnValue(
    Promise.resolve({ data: profile() } as AxiosResponse<PublicUserProfile>) as never,
  );
});

describe('UserHoverCard (issue #482)', () => {
  it('warms the cache on hover and shows the player card after the intent delay', async () => {
    renderCard(ANNA_ID);

    fireEvent.mouseEnter(trigger());
    // The fetch starts immediately — before the card is even visible.
    expect(usersApi.getUserProfile).toHaveBeenCalledWith({ userId: ANNA_ID });
    expect(screen.queryByTestId('user-hover-card')).not.toBeInTheDocument();

    const card = await screen.findByTestId('user-hover-card', {}, { timeout: 2000 });
    expect(card).toHaveTextContent('Anna Krause');
    expect(card).toHaveTextContent('Member since January 2026');
    expect(card).toHaveTextContent('Procurement');
    expect(card).toHaveTextContent('Annotations');
  });

  it('closes when the pointer leaves', async () => {
    renderCard(ANNA_ID);

    fireEvent.mouseEnter(trigger());
    await screen.findByTestId('user-hover-card', {}, { timeout: 2000 });
    fireEvent.mouseLeave(trigger());

    await waitFor(() => expect(screen.queryByTestId('user-hover-card')).not.toBeInTheDocument());
  });

  it('opens on keyboard focus and closes on Escape', async () => {
    renderCard(ANNA_ID);

    fireEvent.focus(trigger());
    await screen.findByTestId('user-hover-card', {}, { timeout: 2000 });
    fireEvent.keyDown(trigger(), { key: 'Escape' });

    await waitFor(() => expect(screen.queryByTestId('user-hover-card')).not.toBeInTheDocument());
  });

  it('never attaches without a real user id (anonymity gate)', async () => {
    renderCard(null);

    fireEvent.mouseEnter(trigger());
    await new Promise((resolve) => setTimeout(resolve, 400));

    expect(screen.queryByTestId('user-hover-card')).not.toBeInTheDocument();
    expect(usersApi.getUserProfile).not.toHaveBeenCalled();
  });

  it('shows your own card too — a roster row that ignores the hover reads as broken', async () => {
    renderCard(SELF_ID);

    fireEvent.mouseEnter(trigger());

    expect(await screen.findByTestId('user-hover-card', {}, { timeout: 2000 })).toBeInTheDocument();
    expect(usersApi.getUserProfile).toHaveBeenCalledWith({ userId: SELF_ID });
  });

  it('links the trigger to the profile by default', () => {
    renderCard(ANNA_ID, true);
    expect(screen.getByRole('link', { name: "View Anna Krause's profile" })).toHaveAttribute(
      'href',
      `/users/${ANNA_ID}`,
    );
  });

  it('prefers the profile slug for the rendered href', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter>
            <UserHoverCard userId={ANNA_ID} slug="anna-krause" profileName="Anna Krause">
              <button type="button">Anna Krause</button>
            </UserHoverCard>
          </MemoryRouter>
        </QueryClientProvider>
      </ThemeProvider>,
    );
    expect(screen.getByRole('link', { name: "View Anna Krause's profile" })).toHaveAttribute(
      'href',
      '/users/anna-krause',
    );
  });

  it('links yourself to /profile', () => {
    renderCard(SELF_ID, true);
    expect(screen.getByRole('link', { name: "View Anna Krause's profile" })).toHaveAttribute(
      'href',
      '/profile',
    );
  });

  it('renders neither link nor card for pseudonymised identities', () => {
    renderCard(null, true);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('renders no link when the caller opts out', () => {
    renderCard(ANNA_ID, false);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });
});
