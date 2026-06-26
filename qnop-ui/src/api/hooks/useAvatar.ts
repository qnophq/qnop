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

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { axiosInstance } from '../config';
import { adminUserKeys } from './useAdminUsers';
import { useAuthStore } from '../../stores/authStore';

/**
 * Hooks for profile-avatar upload/remove (issue #117). The avatar endpoints are hand-written on the
 * backend (multipart in, outside the OpenAPI JSON contract), so they are called through the shared
 * {@link axiosInstance} with a {@code FormData} body rather than a generated client method.
 */
interface AvatarUploadResponse {
  avatarUrl: string | null;
}

async function uploadAvatar(path: string, file: Blob): Promise<AvatarUploadResponse> {
  const form = new FormData();
  // The filename is cosmetic; the backend sniffs the real type from the bytes.
  form.append('file', file, 'avatar');
  const response = await axiosInstance.post<AvatarUploadResponse>(path, form);
  return response.data;
}

/** Uploads the current user's own avatar and reflects the new URL in the shell immediately. */
export function useUploadMyAvatar() {
  const setAvatarUrl = useAuthStore((s) => s.setAvatarUrl);
  return useMutation({
    mutationFn: (file: Blob) => uploadAvatar('/users/me/avatar', file),
    onSuccess: (data) => setAvatarUrl(data.avatarUrl ?? null),
  });
}

/** Removes the current user's own avatar. */
export function useRemoveMyAvatar() {
  const setAvatarUrl = useAuthStore((s) => s.setAvatarUrl);
  return useMutation({
    mutationFn: async () => {
      await axiosInstance.delete('/users/me/avatar');
    },
    onSuccess: () => setAvatarUrl(null),
  });
}

/** Admin: uploads/replaces a given user's avatar; refreshes the admin user list. */
export function useUploadUserAvatar() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, file }: { userId: string; file: Blob }) =>
      uploadAvatar(`/admin/users/${userId}/avatar`, file),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminUserKeys.all }),
  });
}

/** Admin: removes a given user's avatar; refreshes the admin user list. */
export function useRemoveUserAvatar() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (userId: string) => {
      await axiosInstance.delete(`/admin/users/${userId}/avatar`);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminUserKeys.all }),
  });
}
