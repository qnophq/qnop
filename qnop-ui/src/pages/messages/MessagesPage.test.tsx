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
import { MemoryRouter, Route, Routes } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import type { NotificationSummary } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { useMarkAllNotificationsRead, useNotifications } from '../../api/hooks/useNotifications';
import { MessagesPage } from './MessagesPage';

vi.mock('../../api/hooks/useNotifications', () => ({
  useNotifications: vi.fn(),
  useMarkAllNotificationsRead: vi.fn(),
}));

function summary(overrides: Partial<NotificationSummary>): NotificationSummary {
  return {
    id: 'n1',
    type: 'COMMENT_ADDED',
    title: 'Ada replied',
    accessible: true,
    createdAt: '2026-07-01T10:00:00Z',
    ...overrides,
  } as NotificationSummary;
}

const UNREAD = summary({
  id: 'n1',
  type: 'MENTION',
  title: 'Ada mentioned you',
  documentTitle: 'NDA Acme Corp',
  preview: 'can you confirm the cap?',
});
const READ = summary({
  id: 'n2',
  title: 'Mia replied',
  documentTitle: 'Architecture handbook',
  readAt: '2026-07-02T08:00:00Z',
});

const markAllMutate = vi.fn();

function mockList(items: NotificationSummary[], unreadTotal: number, extra = {}) {
  vi.mocked(useNotifications).mockReturnValue({
    data: { items, total: items.length, page: 0, size: 20, unreadTotal },
    isPending: false,
    isError: false,
    refetch: vi.fn(),
    ...extra,
  } as unknown as ReturnType<typeof useNotifications>);
}

function renderPage(entry = '/messages') {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/messages" element={<MessagesPage />} />
          <Route path="/messages/:id" element={<div data-testid="detail-probe" />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(useMarkAllNotificationsRead).mockReturnValue({
    mutate: markAllMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useMarkAllNotificationsRead>);
  mockList([UNREAD, READ], 1);
});

describe('MessagesPage', () => {
  it('lists notifications with their review and quoted excerpt', () => {
    renderPage();

    expect(screen.getByText('Ada mentioned you')).toBeInTheDocument();
    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
    expect(screen.getByText('“can you confirm the cap?”')).toBeInTheDocument();
    expect(screen.getByText('Mia replied')).toBeInTheDocument();
  });

  it('marks unread rows and offers to clear them all', () => {
    renderPage();

    // Exactly one row carries the unread marker — the read one does not.
    expect(screen.getAllByLabelText('Unread')).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: /Mark all read/ }));
    expect(markAllMutate).toHaveBeenCalled();
  });

  it('hides "Mark all read" once nothing is unread', () => {
    mockList([READ], 0);
    renderPage();

    expect(screen.queryByRole('button', { name: /Mark all read/ })).not.toBeInTheDocument();
  });

  it('opens a notification on click', () => {
    renderPage();

    fireEvent.click(screen.getByText('Ada mentioned you'));

    expect(screen.getByTestId('detail-probe')).toBeInTheDocument();
  });

  it('round-trips the read facet through the URL', () => {
    mockList([UNREAD], 1);
    renderPage('/messages?filter=unread');

    // The facet in the URL is what the query asks the server for.
    expect(vi.mocked(useNotifications)).toHaveBeenLastCalledWith(
      expect.objectContaining({ unread: true }),
    );
  });

  it('treats an unknown facet as "all"', () => {
    renderPage('/messages?filter=bogus');

    expect(vi.mocked(useNotifications)).toHaveBeenLastCalledWith(
      expect.objectContaining({ unread: undefined }),
    );
  });

  it('says something useful when the inbox is empty', () => {
    mockList([], 0);
    renderPage();

    expect(screen.getByText('No messages yet')).toBeInTheDocument();
  });

  it('distinguishes "nothing unread" from "no messages at all"', () => {
    mockList([], 0);
    renderPage('/messages?filter=unread');

    expect(screen.getByText('Nothing unread')).toBeInTheDocument();
    expect(screen.getByText('You are all caught up.')).toBeInTheDocument();
  });

  it('switches facets through the chips and restarts paging', () => {
    renderPage('/messages?page=2');

    fireEvent.click(screen.getByRole('button', { name: /^Unread/ }));
    expect(vi.mocked(useNotifications)).toHaveBeenLastCalledWith(
      // Page 3 of "unread" rarely exists — changing the facet drops the page.
      expect.objectContaining({ unread: true, page: 0 }),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Read' }));
    expect(vi.mocked(useNotifications)).toHaveBeenLastCalledWith(
      expect.objectContaining({ unread: false }),
    );
  });

  it('pages through a long inbox', () => {
    vi.mocked(useNotifications).mockReturnValue({
      data: { items: [UNREAD], total: 45, page: 0, size: 20, unreadTotal: 1 },
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useNotifications>);
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Go to page 2' }));

    expect(vi.mocked(useNotifications)).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1 }),
    );
  });

  it('shows skeletons while the first page loads', () => {
    vi.mocked(useNotifications).mockReturnValue({
      data: undefined,
      isPending: true,
      isError: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useNotifications>);
    const { container } = renderPage();

    expect(container.querySelectorAll('.MuiSkeleton-root').length).toBeGreaterThan(0);
  });

  it('shows a branded error state when the inbox cannot be loaded', () => {
    vi.mocked(useNotifications).mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useNotifications>);
    renderPage();

    expect(screen.getByText(/didn't make it to the desk/i)).toBeInTheDocument();
  });
});
