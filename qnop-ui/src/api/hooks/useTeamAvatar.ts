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
import { teamKeys } from './useTeams';
import { myTeamKeys } from './useMyTeams';

/**
 * Hooks for team-avatar upload/remove (issue #509), the team counterpart of {@code useAvatar}. Like
 * the user-avatar endpoints these are hand-written multipart on the backend (outside the OpenAPI
 * JSON contract), so they go through the shared {@link axiosInstance} with a {@code FormData} body.
 * The admin variants hit {@code /admin/teams/{id}/avatar}; the lead self-manage variants hit
 * {@code /teams/{id}/avatar} (403 for a non-lead). Both refresh the team caches so the avatar
 * updates everywhere the team is shown.
 */
interface AvatarUploadResponse {
  avatarUrl: string | null;
}

async function uploadTeamAvatar(path: string, file: Blob): Promise<AvatarUploadResponse> {
  const form = new FormData();
  // The filename is cosmetic; the backend sniffs the real type from the bytes.
  form.append('file', file, 'avatar');
  const response = await axiosInstance.post<AvatarUploadResponse>(path, form);
  return response.data;
}

function useInvalidateTeams() {
  const queryClient = useQueryClient();
  return () => {
    queryClient.invalidateQueries({ queryKey: teamKeys.all });
    queryClient.invalidateQueries({ queryKey: myTeamKeys.all });
  };
}

/** Admin: uploads/replaces a team's avatar and returns its new URL. */
export function useUploadTeamAvatar() {
  const invalidate = useInvalidateTeams();
  return useMutation({
    mutationFn: ({ teamId, file }: { teamId: string; file: Blob }) =>
      uploadTeamAvatar(`/admin/teams/${teamId}/avatar`, file),
    onSuccess: invalidate,
  });
}

/** Admin: removes a team's avatar. */
export function useRemoveTeamAvatar() {
  const invalidate = useInvalidateTeams();
  return useMutation({
    mutationFn: async (teamId: string) => {
      await axiosInstance.delete(`/admin/teams/${teamId}/avatar`);
    },
    onSuccess: invalidate,
  });
}

/** Team lead (or admin): uploads/replaces the avatar of a team they lead. */
export function useUploadMyTeamAvatar() {
  const invalidate = useInvalidateTeams();
  return useMutation({
    mutationFn: ({ teamId, file }: { teamId: string; file: Blob }) =>
      uploadTeamAvatar(`/teams/${teamId}/avatar`, file),
    onSuccess: invalidate,
  });
}

/** Team lead (or admin): removes the avatar of a team they lead. */
export function useRemoveMyTeamAvatar() {
  const invalidate = useInvalidateTeams();
  return useMutation({
    mutationFn: async (teamId: string) => {
      await axiosInstance.delete(`/teams/${teamId}/avatar`);
    },
    onSuccess: invalidate,
  });
}
