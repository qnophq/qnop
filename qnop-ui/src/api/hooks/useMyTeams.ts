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
import type { AdminTeamDetail, MyTeamListResponse, TeamRole } from '../generated';
import { teamsApi } from '../config';

/**
 * Non-admin team-lead hooks (issue #470) — the "My Teams" self-management surface.
 * These mirror {@link useTeams} but call the `/teams/**` client (a LEAD of the
 * team, or an ADMIN) rather than the admin `/admin/teams/**` client, and live under
 * a separate query-key namespace so the two caches never collide.
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

/** A single team the caller leads (or any team for an admin), with its members. */
export function useMyTeam(id: string) {
  return useQuery<AdminTeamDetail>({
    queryKey: myTeamKeys.detail(id),
    queryFn: async () => {
      const response = await teamsApi.getMyTeam({ teamId: id });
      return response.data;
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
