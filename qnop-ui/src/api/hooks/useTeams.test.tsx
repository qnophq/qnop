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
import type { AdminTeamDetail, AdminTeamListResponse } from '../generated';
import {
  teamKeys,
  useAddTeamMember,
  useCreateTeam,
  useDeleteTeam,
  useRemoveTeamMember,
  useSetTeamMemberRole,
  useTeam,
  useTeams,
  useUpdateTeam,
} from './useTeams';
import { adminTeamsApi } from '../config';

vi.mock('../config', () => ({
  adminTeamsApi: {
    listTeams: vi.fn(),
    getTeam: vi.fn(),
    createTeam: vi.fn(),
    updateTeam: vi.fn(),
    deleteTeam: vi.fn(),
    addTeamMember: vi.fn(),
    setTeamMemberRole: vi.fn(),
    removeTeamMember: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

const EMPTY_PAGE: AdminTeamListResponse = { items: [], total: 0, page: 0, size: 20 };
const TEAM: AdminTeamDetail = {
  id: 't1',
  name: 'Core',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  members: [],
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('teamKeys', () => {
  it('namespaces list and detail keys', () => {
    expect(teamKeys.list({ page: 0, size: 20 })).toEqual([
      'admin',
      'teams',
      'list',
      { page: 0, size: 20 },
    ]);
    expect(teamKeys.detail('t1')).toEqual(['admin', 'teams', 'detail', 't1']);
  });
});

describe('useTeams / useTeam', () => {
  it('lists teams with query and pagination', async () => {
    vi.mocked(adminTeamsApi.listTeams).mockResolvedValue({ data: EMPTY_PAGE } as Awaited<
      ReturnType<typeof adminTeamsApi.listTeams>
    >);

    const { result } = renderHook(() => useTeams({ q: 'core', page: 1, size: 20 }), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(adminTeamsApi.listTeams).toHaveBeenCalledWith({ q: 'core', page: 1, size: 20 });
  });

  it('fetches a single team', async () => {
    vi.mocked(adminTeamsApi.getTeam).mockResolvedValue({ data: TEAM } as Awaited<
      ReturnType<typeof adminTeamsApi.getTeam>
    >);

    const { result } = renderHook(() => useTeam('t1'), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(adminTeamsApi.getTeam).toHaveBeenCalledWith({ teamId: 't1' });
    expect(result.current.data?.name).toBe('Core');
  });
});

describe('team mutations', () => {
  it('creates, updates and deletes a team', async () => {
    vi.mocked(adminTeamsApi.createTeam).mockResolvedValue({ data: {} } as Awaited<
      ReturnType<typeof adminTeamsApi.createTeam>
    >);
    vi.mocked(adminTeamsApi.updateTeam).mockResolvedValue({ data: {} } as Awaited<
      ReturnType<typeof adminTeamsApi.updateTeam>
    >);
    vi.mocked(adminTeamsApi.deleteTeam).mockResolvedValue({ data: undefined } as Awaited<
      ReturnType<typeof adminTeamsApi.deleteTeam>
    >);

    const create = renderHook(() => useCreateTeam(), { wrapper });
    await create.result.current.mutateAsync({ name: 'Core' });
    expect(adminTeamsApi.createTeam).toHaveBeenCalledWith({
      adminTeamCreateRequest: { name: 'Core' },
    });

    const update = renderHook(() => useUpdateTeam(), { wrapper });
    await update.result.current.mutateAsync({ id: 't1', request: { enabled: false } });
    expect(adminTeamsApi.updateTeam).toHaveBeenCalledWith({
      teamId: 't1',
      adminTeamUpdateRequest: { enabled: false },
    });

    const del = renderHook(() => useDeleteTeam(), { wrapper });
    await del.result.current.mutateAsync('t1');
    expect(adminTeamsApi.deleteTeam).toHaveBeenCalledWith({ teamId: 't1' });
  });

  it('adds, re-roles and removes a member', async () => {
    vi.mocked(adminTeamsApi.addTeamMember).mockResolvedValue({ data: {} } as Awaited<
      ReturnType<typeof adminTeamsApi.addTeamMember>
    >);
    vi.mocked(adminTeamsApi.setTeamMemberRole).mockResolvedValue({ data: {} } as Awaited<
      ReturnType<typeof adminTeamsApi.setTeamMemberRole>
    >);
    vi.mocked(adminTeamsApi.removeTeamMember).mockResolvedValue({ data: undefined } as Awaited<
      ReturnType<typeof adminTeamsApi.removeTeamMember>
    >);

    const add = renderHook(() => useAddTeamMember(), { wrapper });
    await add.result.current.mutateAsync({ teamId: 't1', userId: 'u1', teamRole: 'LEAD' });
    expect(adminTeamsApi.addTeamMember).toHaveBeenCalledWith({
      teamId: 't1',
      adminTeamMemberRequest: { userId: 'u1', teamRole: 'LEAD' },
    });

    const role = renderHook(() => useSetTeamMemberRole(), { wrapper });
    await role.result.current.mutateAsync({ teamId: 't1', userId: 'u1', teamRole: 'MEMBER' });
    expect(adminTeamsApi.setTeamMemberRole).toHaveBeenCalledWith({
      teamId: 't1',
      userId: 'u1',
      adminTeamMemberRoleUpdateRequest: { teamRole: 'MEMBER' },
    });

    const remove = renderHook(() => useRemoveTeamMember(), { wrapper });
    await remove.result.current.mutateAsync({ teamId: 't1', userId: 'u1' });
    expect(adminTeamsApi.removeTeamMember).toHaveBeenCalledWith({ teamId: 't1', userId: 'u1' });
  });
});
