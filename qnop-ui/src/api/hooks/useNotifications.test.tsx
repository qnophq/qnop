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

import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { NotificationDetail, NotificationPage } from '../generated';
import {
  notificationKeys,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotification,
  useNotifications,
  useUnreadCount,
} from './useNotifications';
import { notificationsApi } from '../config';

const EMPTY_PAGE: NotificationPage = { items: [], total: 0, page: 0, size: 20, unreadTotal: 0 };
const DETAIL = {
  id: 'n1',
  type: 'MENTION',
  title: 'Ada mentioned you',
  body: 'Ada mentioned you in a comment.',
  accessible: true,
  createdAt: '2026-07-01T10:00:00Z',
} as NotificationDetail;

vi.mock('../config', () => ({
  notificationsApi: {
    listNotifications: vi.fn(),
    getUnreadNotificationCount: vi.fn(),
    getNotification: vi.fn(),
    markNotificationRead: vi.fn(),
    markAllNotificationsRead: vi.fn(),
  },
}));

let queryClient: QueryClient;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
});

describe('notificationKeys', () => {
  it('namespaces the inbox, the badge and each detail separately', () => {
    const params = { unread: true, page: 1, size: 20 };
    expect(notificationKeys.list(params)).toEqual(['notifications', 'list', params]);
    expect(notificationKeys.unread()).toEqual(['notifications', 'unread']);
    expect(notificationKeys.detail('n1')).toEqual(['notifications', 'detail', 'n1']);
    // Everything hangs off one root, so a mutation can invalidate the lot.
    expect(notificationKeys.detail('n1')[0]).toBe(notificationKeys.all[0]);
  });
});

describe('useNotifications', () => {
  it('passes the facet and pagination through and returns the page', async () => {
    vi.mocked(notificationsApi.listNotifications).mockResolvedValue({
      data: EMPTY_PAGE,
    } as Awaited<ReturnType<typeof notificationsApi.listNotifications>>);

    const { result } = renderHook(() => useNotifications({ unread: true, page: 2, size: 50 }), {
      wrapper,
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(notificationsApi.listNotifications).toHaveBeenCalledWith({
      unread: true,
      page: 2,
      size: 50,
    });
    expect(result.current.data).toEqual(EMPTY_PAGE);
  });
});

describe('useUnreadCount', () => {
  it('unwraps the badge number', async () => {
    vi.mocked(notificationsApi.getUnreadNotificationCount).mockResolvedValue({
      data: { unread: 7 },
    } as Awaited<ReturnType<typeof notificationsApi.getUnreadNotificationCount>>);

    const { result } = renderHook(() => useUnreadCount(), { wrapper });

    await waitFor(() => expect(result.current.data).toBe(7));
  });
});

describe('useNotification', () => {
  it('fetches one notification by id', async () => {
    vi.mocked(notificationsApi.getNotification).mockResolvedValue({ data: DETAIL } as Awaited<
      ReturnType<typeof notificationsApi.getNotification>
    >);

    const { result } = renderHook(() => useNotification('n1'), { wrapper });

    await waitFor(() => expect(result.current.data).toEqual(DETAIL));
    expect(notificationsApi.getNotification).toHaveBeenCalledWith({ notificationId: 'n1' });
  });

  it('stays idle without an id, so a half-built route never fires a request', () => {
    const { result } = renderHook(() => useNotification(''), { wrapper });

    expect(result.current.fetchStatus).toBe('idle');
    expect(notificationsApi.getNotification).not.toHaveBeenCalled();
  });
});

describe('marking read', () => {
  it('marks one and then refreshes every surface that shows a count', async () => {
    vi.mocked(notificationsApi.markNotificationRead).mockResolvedValue(
      {} as Awaited<ReturnType<typeof notificationsApi.markNotificationRead>>,
    );
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    const { result } = renderHook(() => useMarkNotificationRead(), { wrapper });
    result.current.mutate('n1');

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(notificationsApi.markNotificationRead).toHaveBeenCalledWith({ notificationId: 'n1' });
    // The badge, the quickview and the inbox all hang off the same root key.
    expect(invalidate).toHaveBeenCalledWith({ queryKey: notificationKeys.all });
  });

  it('marks all and refreshes the same surfaces', async () => {
    vi.mocked(notificationsApi.markAllNotificationsRead).mockResolvedValue(
      {} as Awaited<ReturnType<typeof notificationsApi.markAllNotificationsRead>>,
    );
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    const { result } = renderHook(() => useMarkAllNotificationsRead(), { wrapper });
    result.current.mutate();

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: notificationKeys.all });
  });
});
