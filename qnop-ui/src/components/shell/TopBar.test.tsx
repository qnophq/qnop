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
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { hasTouchTarget } from '../../test/cssRules';
import { TopBar } from './TopBar';

// The embedded GlobalSearch (#540) queries through TanStack Query; the bar's
// own tests only need the input to exist, so the hook is stubbed idle.
vi.mock('../../api/hooks/useSearch', () => ({
  SEARCH_MIN_LENGTH: 2,
  useSearchQuick: () => ({ data: undefined, isPending: false, isError: false }),
}));

const unreadCount = vi.fn(() => 0);
vi.mock('../../api/hooks/useNotifications', () => ({
  useUnreadCount: () => ({ data: unreadCount() }),
  useNotifications: () => ({
    data: {
      items: [
        {
          id: 'n1',
          type: 'MENTION',
          title: 'Ada mentioned you',
          documentTitle: 'NDA Acme Corp',
          accessible: true,
          createdAt: '2026-07-01T10:00:00Z',
        },
      ],
      total: 1,
      page: 0,
      size: 6,
      unreadTotal: unreadCount(),
    },
    isPending: false,
  }),
  useMarkAllNotificationsRead: () => ({ mutate: vi.fn(), isPending: false }),
}));

function renderTopBar() {
  return render(
    // The quickview's rows resolve each actor's profile through the query cache.
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter>
          <TopBar isMobile={false} onToggleSidebar={vi.fn()} />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('TopBar notifications (#538)', () => {
  it('opens the notification quickview on bell click and closes it again', async () => {
    const user = userEvent.setup();
    unreadCount.mockReturnValue(1);
    renderTopBar();

    expect(screen.queryByText('Ada mentioned you')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Notifications/ }));
    expect(await screen.findByText('Ada mentioned you')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'See all messages' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByText('Ada mentioned you')).not.toBeInTheDocument();
  });

  it('carries the real unread count on the bell, and announces it', () => {
    unreadCount.mockReturnValue(3);
    renderTopBar();

    // The badge is a number now, not the decorative dot it used to be (#514).
    expect(screen.getByRole('button', { name: 'Notifications, 3 unread' })).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('shows no badge when nothing is unread', () => {
    unreadCount.mockReturnValue(0);
    renderTopBar();

    const bell = screen.getByRole('button', { name: 'Notifications' });
    expect(bell.textContent).not.toMatch(/\d/);
  });

  it('carries the real global search input (#540) instead of the old trigger', () => {
    renderTopBar();

    expect(screen.getByLabelText('Search reviews, people and teams')).toBeInTheDocument();
    expect(screen.queryByText(/jump to any review/i)).not.toBeInTheDocument();
  });
});

describe('TopBar touch targets (#724)', () => {
  it.each(['Toggle menu', 'Switch to dark mode', 'Notifications'])(
    'gives "%s" a 44 px hit area while keeping the small icon button',
    (name) => {
      renderTopBar();

      const button = screen.getByRole('button', { name });

      expect(button.className).toMatch(/MuiIconButton-sizeSmall/);
      expect(hasTouchTarget(button)).toBe(true);
    },
  );
});
