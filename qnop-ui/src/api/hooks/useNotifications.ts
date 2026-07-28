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

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { NotificationDetail, NotificationPage } from '../generated';
import { notificationsApi } from '../config';

export interface NotificationListParams {
  unread?: boolean;
  page?: number;
  size?: number;
}

export const notificationKeys = {
  all: ['notifications'] as const,
  lists: () => [...notificationKeys.all, 'list'] as const,
  list: (params: NotificationListParams) => [...notificationKeys.lists(), params] as const,
  detail: (id: string) => [...notificationKeys.all, 'detail', id] as const,
  unread: () => [...notificationKeys.all, 'unread'] as const,
};

/**
 * How often the shell re-asks for the badge. The inbox is DB-backed and polled
 * (ADR-0013 defers Redis); a minute is frequent enough to feel live without
 * turning an idle tab into a request generator. Live push over the SSE
 * transport is the later upgrade, and nothing here assumes polling.
 */
const BADGE_POLL_MS = 60_000;

/** One page of the caller's inbox. */
export function useNotifications(params: NotificationListParams = {}) {
  return useQuery<NotificationPage>({
    queryKey: notificationKeys.list(params),
    queryFn: async () => {
      const response = await notificationsApi.listNotifications(params);
      return response.data;
    },
    placeholderData: keepPreviousData,
  });
}

/** The bell's number — polled, and refreshed whenever the tab regains focus. */
export function useUnreadCount() {
  return useQuery<number>({
    queryKey: notificationKeys.unread(),
    queryFn: async () => {
      const response = await notificationsApi.getUnreadNotificationCount();
      return response.data.unread;
    },
    refetchInterval: BADGE_POLL_MS,
    refetchOnWindowFocus: true,
  });
}

export function useNotification(notificationId: string, enabled = true) {
  return useQuery<NotificationDetail>({
    queryKey: notificationKeys.detail(notificationId),
    queryFn: async () => {
      const response = await notificationsApi.getNotification({ notificationId });
      return response.data;
    },
    enabled: enabled && notificationId !== '',
  });
}

/** Marks one notification read, then refreshes every surface that shows a count. */
export function useMarkNotificationRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (notificationId: string) => {
      await notificationsApi.markNotificationRead({ notificationId });
      return notificationId;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}

export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      await notificationsApi.markAllNotificationsRead();
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}
