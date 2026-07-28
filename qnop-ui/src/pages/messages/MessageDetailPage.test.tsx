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
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import type { NotificationDetail } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { useMarkNotificationRead, useNotification } from '../../api/hooks/useNotifications';
import { MessageDetailPage } from './MessageDetailPage';

vi.mock('../../api/hooks/useNotifications', () => ({
  useNotification: vi.fn(),
  useMarkNotificationRead: vi.fn(),
}));

const markReadMutate = vi.fn();

/**
 * The generated client types the contract's nullable fields as `string |
 * undefined`, while the server actually sends `null`. The fixtures keep the
 * real wire shape and loosen the override type rather than pretending
 * otherwise — the component's falsy checks handle both.
 */
type DetailOverrides = Partial<Record<keyof NotificationDetail, unknown>>;

function detail(overrides: DetailOverrides = {}): NotificationDetail {
  return {
    id: 'n1',
    type: 'MENTION',
    title: 'Ada mentioned you',
    body: 'Ada mentioned you in a comment on “NDA Acme Corp”.',
    preview: 'can you confirm the cap?',
    actorName: 'Ada Admin',
    actorSlug: 'ada-admin',
    documentTitle: 'NDA Acme Corp',
    actionPath: '/reviews/nda-acme?annotation=a1',
    actionLabel: 'Open annotation',
    accessible: true,
    createdAt: '2026-07-01T10:00:00Z',
    ...overrides,
  } as NotificationDetail;
}

function mockDetail(value: DetailOverrides | null, extra = {}) {
  vi.mocked(useNotification).mockReturnValue({
    data: value === null ? undefined : detail(value),
    isPending: false,
    isError: false,
    ...extra,
  } as unknown as ReturnType<typeof useNotification>);
}

function renderPage() {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter initialEntries={['/messages/n1']}>
          <Routes>
            <Route path="/messages/:notificationId" element={<MessageDetailPage />} />
            <Route path="/reviews/:documentId" element={<div data-testid="review-probe" />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(useMarkNotificationRead).mockReturnValue({
    mutate: markReadMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useMarkNotificationRead>);
  mockDetail({});
});

describe('MessageDetailPage', () => {
  it('renders the formatted message, its excerpt and the review it belongs to', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Ada mentioned you' })).toBeInTheDocument();
    expect(
      screen.getByText('Ada mentioned you in a comment on “NDA Acme Corp”.'),
    ).toBeInTheDocument();
    expect(screen.getByText('“can you confirm the cap?”')).toBeInTheDocument();
  });

  it('wears its type as a crest so the kind is legible before the words', () => {
    renderPage();

    // The type label is the eyebrow above the headline; the icon carries the
    // same identity in colour (asserted in notificationMeta.test).
    expect(screen.getByText('Mention')).toBeInTheDocument();
  });

  it('shows the sender with a face that links to their profile', () => {
    renderPage();

    expect(screen.getByText('AA')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Ada Admin/ })).toHaveAttribute(
      'href',
      '/users/ada-admin',
    );
  });

  it('gives a pseudonymised sender no avatar link at all (ADR-0038)', () => {
    // No slug is exactly what an anonymous review ships — a link or a hover
    // card here would undo the pseudonym the name carefully keeps.
    mockDetail({ actorName: 'Participant 2', actorSlug: null });
    renderPage();

    expect(screen.getByText('Participant 2')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Participant 2/ })).not.toBeInTheDocument();
  });

  it('marks an unread notification read when it is opened', () => {
    renderPage();

    expect(markReadMutate).toHaveBeenCalledWith('n1');
  });

  it('leaves an already-read notification alone', () => {
    mockDetail({ readAt: '2026-07-02T08:00:00Z' });
    renderPage();

    expect(markReadMutate).not.toHaveBeenCalled();
  });

  it('follows the deep link to what the notification is about', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /Open annotation/ }));

    expect(screen.getByTestId('review-probe')).toBeInTheDocument();
  });

  it('offers no deep link once the review is out of reach', () => {
    mockDetail({
      accessible: false,
      actionPath: null,
      actionLabel: null,
      documentTitle: null,
      title: 'A review you no longer have access to',
      body: 'This notification refers to a review that is no longer available to you.',
    });
    renderPage();

    expect(screen.getByText('No longer available')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Open/ })).not.toBeInTheDocument();
  });

  it('shows a branded 404 for an unknown notification', () => {
    mockDetail(null, { isError: true });
    renderPage();

    expect(screen.getByText(/already been filed away/i)).toBeInTheDocument();
  });
});
