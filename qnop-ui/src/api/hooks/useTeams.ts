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
  AdminTeamCreateRequest,
  AdminTeamDetail,
  AdminTeamListResponse,
  AdminTeamUpdateRequest,
  TeamRole,
} from '../generated';
import { adminTeamsApi } from '../config';

export interface TeamListParams {
  q?: string;
  page: number;
  size: number;
}

export const teamKeys = {
  all: ['admin', 'teams'] as const,
  list: (params: TeamListParams) => [...teamKeys.all, 'list', params] as const,
  detail: (id: string) => [...teamKeys.all, 'detail', id] as const,
};

/** A page of teams for the admin list (keeps the previous page while loading). */
export function useTeams(params: TeamListParams) {
  return useQuery<AdminTeamListResponse>({
    queryKey: teamKeys.list(params),
    queryFn: async () => {
      const response = await adminTeamsApi.listTeams({
        q: params.q || undefined,
        page: params.page,
        size: params.size,
      });
      return response.data;
    },
    placeholderData: keepPreviousData,
  });
}

/** A single team with its members. */
export function useTeam(id: string) {
  return useQuery<AdminTeamDetail>({
    queryKey: teamKeys.detail(id),
    queryFn: async () => {
      const response = await adminTeamsApi.getTeam({ teamId: id });
      return response.data;
    },
  });
}

export function useCreateTeam() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: AdminTeamCreateRequest) => {
      const response = await adminTeamsApi.createTeam({ adminTeamCreateRequest: request });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: teamKeys.all }),
  });
}

export function useUpdateTeam() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { id: string; request: AdminTeamUpdateRequest }) => {
      const response = await adminTeamsApi.updateTeam({
        teamId: vars.id,
        adminTeamUpdateRequest: vars.request,
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: teamKeys.all }),
  });
}

export function useDeleteTeam() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await adminTeamsApi.deleteTeam({ teamId: id });
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: teamKeys.all }),
  });
}

export function useAddTeamMember() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { teamId: string; userId: string; teamRole: TeamRole }) => {
      const response = await adminTeamsApi.addTeamMember({
        teamId: vars.teamId,
        adminTeamMemberRequest: { userId: vars.userId, teamRole: vars.teamRole },
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: teamKeys.all }),
  });
}

export function useSetTeamMemberRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { teamId: string; userId: string; teamRole: TeamRole }) => {
      const response = await adminTeamsApi.setTeamMemberRole({
        teamId: vars.teamId,
        userId: vars.userId,
        adminTeamMemberRoleUpdateRequest: { teamRole: vars.teamRole },
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: teamKeys.all }),
  });
}

export function useRemoveTeamMember() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { teamId: string; userId: string }) => {
      await adminTeamsApi.removeTeamMember({ teamId: vars.teamId, userId: vars.userId });
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: teamKeys.all }),
  });
}
