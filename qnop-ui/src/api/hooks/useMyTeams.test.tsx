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
import type { MyTeamListResponse, TeamDetail } from '../generated';
import {
  myTeamKeys,
  useAcceptTeamInvitation,
  useAddMyTeamMember,
  useDeclineTeamInvitation,
  useMyTeam,
  useMyTeamInvitations,
  useMyTeams,
  useRemoveMyTeamMember,
  useRevokeMyTeamInvitation,
  useSetMyTeamMemberRole,
} from './useMyTeams';
import { teamsApi } from '../config';

vi.mock('../config', () => ({
  teamsApi: {
    listMyTeams: vi.fn(),
    getMyTeam: vi.fn(),
    addMyTeamMember: vi.fn(),
    setMyTeamMemberRole: vi.fn(),
    removeMyTeamMember: vi.fn(),
    listMyTeamInvitations: vi.fn(),
    acceptMyTeamInvitation: vi.fn(),
    declineMyTeamInvitation: vi.fn(),
    revokeMyTeamInvitation: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

const MINE: MyTeamListResponse = {
  items: [{ teamId: 't1', name: 'Core', teamRole: 'LEAD', memberCount: 4 }],
};
const TEAM: TeamDetail = {
  id: 't1',
  name: 'Core',
  viewerRole: 'LEAD',
  viewerCanManage: true,
  profileShowMembers: false,
  profileShowReviews: false,
  members: [],
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('myTeamKeys', () => {
  it('uses a namespace separate from the admin team keys', () => {
    expect(myTeamKeys.list()).toEqual(['my', 'teams', 'list']);
    expect(myTeamKeys.detail('t1')).toEqual(['my', 'teams', 'detail', 't1']);
  });
});

describe('useMyTeams / useMyTeam', () => {
  it('lists the caller’s own teams via the non-admin client', async () => {
    vi.mocked(teamsApi.listMyTeams).mockResolvedValue({ data: MINE } as Awaited<
      ReturnType<typeof teamsApi.listMyTeams>
    >);

    const { result } = renderHook(() => useMyTeams(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(teamsApi.listMyTeams).toHaveBeenCalledTimes(1);
    expect(result.current.data?.items[0].teamRole).toBe('LEAD');
  });

  it('fetches a single led team', async () => {
    vi.mocked(teamsApi.getMyTeam).mockResolvedValue({ data: TEAM } as Awaited<
      ReturnType<typeof teamsApi.getMyTeam>
    >);

    const { result } = renderHook(() => useMyTeam('t1'), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(teamsApi.getMyTeam).toHaveBeenCalledWith({ teamId: 't1' });
  });
});

describe('my-team mutations', () => {
  it('adds, re-roles and removes a member through the non-admin client', async () => {
    vi.mocked(teamsApi.addMyTeamMember).mockResolvedValue({ data: {} } as Awaited<
      ReturnType<typeof teamsApi.addMyTeamMember>
    >);
    vi.mocked(teamsApi.setMyTeamMemberRole).mockResolvedValue({ data: {} } as Awaited<
      ReturnType<typeof teamsApi.setMyTeamMemberRole>
    >);
    vi.mocked(teamsApi.removeMyTeamMember).mockResolvedValue({ data: undefined } as Awaited<
      ReturnType<typeof teamsApi.removeMyTeamMember>
    >);

    const add = renderHook(() => useAddMyTeamMember(), { wrapper });
    await add.result.current.mutateAsync({ teamId: 't1', userId: 'u1', teamRole: 'MEMBER' });
    expect(teamsApi.addMyTeamMember).toHaveBeenCalledWith({
      teamId: 't1',
      adminTeamMemberRequest: { userId: 'u1', teamRole: 'MEMBER' },
    });

    const role = renderHook(() => useSetMyTeamMemberRole(), { wrapper });
    await role.result.current.mutateAsync({ teamId: 't1', userId: 'u1', teamRole: 'LEAD' });
    expect(teamsApi.setMyTeamMemberRole).toHaveBeenCalledWith({
      teamId: 't1',
      userId: 'u1',
      adminTeamMemberRoleUpdateRequest: { teamRole: 'LEAD' },
    });

    const remove = renderHook(() => useRemoveMyTeamMember(), { wrapper });
    await remove.result.current.mutateAsync({ teamId: 't1', userId: 'u1' });
    expect(teamsApi.removeMyTeamMember).toHaveBeenCalledWith({ teamId: 't1', userId: 'u1' });
  });
});

describe('team invitations (#936)', () => {
  it('lists, accepts, declines and revokes through the non-admin client', async () => {
    vi.mocked(teamsApi.listMyTeamInvitations).mockResolvedValue({
      data: { items: [{ teamId: 't1', teamName: 'Core', invitedAt: '2026-11-01T10:00:00Z' }] },
    } as Awaited<ReturnType<typeof teamsApi.listMyTeamInvitations>>);
    vi.mocked(teamsApi.acceptMyTeamInvitation).mockResolvedValue({ data: {} } as Awaited<
      ReturnType<typeof teamsApi.acceptMyTeamInvitation>
    >);
    vi.mocked(teamsApi.declineMyTeamInvitation).mockResolvedValue({ data: undefined } as Awaited<
      ReturnType<typeof teamsApi.declineMyTeamInvitation>
    >);
    vi.mocked(teamsApi.revokeMyTeamInvitation).mockResolvedValue({ data: undefined } as Awaited<
      ReturnType<typeof teamsApi.revokeMyTeamInvitation>
    >);

    const list = renderHook(() => useMyTeamInvitations(), { wrapper });
    await waitFor(() => expect(list.result.current.isSuccess).toBe(true));
    expect(list.result.current.data?.items[0].teamName).toBe('Core');
    expect(myTeamKeys.invitations()).toEqual(['my', 'teams', 'invitations']);

    const accept = renderHook(() => useAcceptTeamInvitation(), { wrapper });
    await accept.result.current.mutateAsync('t1');
    expect(teamsApi.acceptMyTeamInvitation).toHaveBeenCalledWith({ teamId: 't1' });

    const decline = renderHook(() => useDeclineTeamInvitation(), { wrapper });
    await decline.result.current.mutateAsync('t2');
    expect(teamsApi.declineMyTeamInvitation).toHaveBeenCalledWith({ teamId: 't2' });

    const revoke = renderHook(() => useRevokeMyTeamInvitation(), { wrapper });
    await revoke.result.current.mutateAsync({ teamId: 't1', userId: 'u9' });
    expect(teamsApi.revokeMyTeamInvitation).toHaveBeenCalledWith({ teamId: 't1', userId: 'u9' });
  });
});
