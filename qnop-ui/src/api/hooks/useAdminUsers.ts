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
import type {
  AdminPasswordResetResponse,
  AdminUserCreateRequest,
  AdminUserListResponse,
  AdminUserUpdateRequest,
  UserRole,
} from '../generated';
import { adminUsersApi } from '../config';

/** Query parameters for the admin user list. */
export interface AdminUserListParams {
  q?: string;
  role?: UserRole;
  enabled?: boolean;
  sort?: string;
  page: number;
  size: number;
}

export const adminUserKeys = {
  all: ['admin', 'users'] as const,
  list: (params: AdminUserListParams) => [...adminUserKeys.all, 'list', params] as const,
};

/**
 * A page of users for the admin list. Keeps the previous page visible while the
 * next one loads, so pagination, search, filtering and sorting do not flash an
 * empty table.
 */
export function useAdminUsers(params: AdminUserListParams) {
  return useQuery<AdminUserListResponse>({
    queryKey: adminUserKeys.list(params),
    queryFn: async () => {
      const response = await adminUsersApi.listUsers({
        q: params.q || undefined,
        role: params.role,
        enabled: params.enabled,
        sort: params.sort,
        page: params.page,
        size: params.size,
      });
      return response.data;
    },
    placeholderData: keepPreviousData,
  });
}

/** Creates (or invites, when no initialPassword is given) a user, then refreshes the list. */
export function useCreateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: AdminUserCreateRequest) => {
      const response = await adminUsersApi.createUser({ adminUserCreateRequest: request });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminUserKeys.all }),
  });
}

/** Updates a user's display name, role and/or enabled state, then refreshes the list. */
export function useUpdateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { id: string; request: AdminUserUpdateRequest }) => {
      const response = await adminUsersApi.updateUser({
        userId: vars.id,
        adminUserUpdateRequest: vars.request,
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminUserKeys.all }),
  });
}

/** Deletes a user, then refreshes the list. */
export function useDeleteUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await adminUsersApi.deleteUser({ userId: id });
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminUserKeys.all }),
  });
}

/**
 * Admin password reset: revokes the user's sessions and emails a reset link.
 * Returns the outcome ({@code emailSent}, and a fallback {@code resetUrl} when
 * email could not be sent).
 */
export function useSendUserPasswordReset() {
  return useMutation<AdminPasswordResetResponse, unknown, string>({
    mutationFn: async (id: string) => {
      const response = await adminUsersApi.sendUserPasswordReset({ userId: id });
      return response.data;
    },
  });
}
