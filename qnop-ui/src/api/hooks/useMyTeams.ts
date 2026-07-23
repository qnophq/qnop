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

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { MyTeamListResponse, TeamDetail, TeamRole } from '../generated';
import { teamsApi } from '../config';
import { teamKeys } from './useTeams';

/**
 * Non-admin "My Teams" hooks (issue #470). Every authenticated user reaches the
 * surface: {@link useMyTeams} lists the teams they belong to, {@link useMyTeam}
 * views one team's roster (read-only for a plain member, manageable for a LEAD —
 * the detail's `viewerCanManage` flag says which). The mutations are LEAD-or-admin
 * only, enforced server-side. Calls the `/teams/**` client, under a query-key
 * namespace separate from the admin `/admin/teams/**` hooks.
 */
export const myTeamKeys = {
  all: ['my', 'teams'] as const,
  list: () => [...myTeamKeys.all, 'list'] as const,
  detail: (id: string) => [...myTeamKeys.all, 'detail', id] as const,
};

/** The teams the caller belongs to, each flagged with the caller's team role. */
export function useMyTeams() {
  return useQuery<MyTeamListResponse>({
    queryKey: myTeamKeys.list(),
    queryFn: async () => {
      const response = await teamsApi.listMyTeams();
      return response.data;
    },
  });
}

/** One team's roster, with the caller's role and whether they may manage it. */
export function useMyTeam(id: string) {
  return useQuery<TeamDetail>({
    queryKey: myTeamKeys.detail(id),
    queryFn: async () => {
      const response = await teamsApi.getMyTeam({ teamId: id });
      return response.data;
    },
  });
}

/**
 * A lead's (or admin's) self-manage team update (issue #509 follow-up):
 * currently only the description. Refreshes both team cache families so the
 * new text shows on My Teams and the admin surfaces alike.
 */
export function useUpdateMyTeam() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { teamId: string; description: string | null }) => {
      const response = await teamsApi.updateMyTeam({
        teamId: vars.teamId,
        myTeamUpdateRequest: { description: vars.description ?? undefined },
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: myTeamKeys.all });
      queryClient.invalidateQueries({ queryKey: teamKeys.all });
    },
  });
}

export function useAddMyTeamMember() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { teamId: string; userId: string; teamRole: TeamRole }) => {
      const response = await teamsApi.addMyTeamMember({
        teamId: vars.teamId,
        adminTeamMemberRequest: { userId: vars.userId, teamRole: vars.teamRole },
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: myTeamKeys.all }),
  });
}

export function useSetMyTeamMemberRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { teamId: string; userId: string; teamRole: TeamRole }) => {
      const response = await teamsApi.setMyTeamMemberRole({
        teamId: vars.teamId,
        userId: vars.userId,
        adminTeamMemberRoleUpdateRequest: { teamRole: vars.teamRole },
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: myTeamKeys.all }),
  });
}

export function useRemoveMyTeamMember() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { teamId: string; userId: string }) => {
      await teamsApi.removeMyTeamMember({ teamId: vars.teamId, userId: vars.userId });
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: myTeamKeys.all }),
  });
}
