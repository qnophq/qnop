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
import type { AdminUserListResponse } from '../generated';
import {
  adminUserKeys,
  useAdminUsers,
  useCreateUser,
  useSendUserPasswordReset,
  useUpdateUser,
} from './useAdminUsers';
import { adminUsersApi } from '../config';

const EMPTY_PAGE: AdminUserListResponse = { items: [], total: 0, page: 0, size: 20 };

vi.mock('../config', () => ({
  adminUsersApi: {
    listUsers: vi.fn(),
    createUser: vi.fn(),
    updateUser: vi.fn(),
    sendUserPasswordReset: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('adminUserKeys', () => {
  it('namespaces list keys by params', () => {
    const params = { q: 'a', page: 1, size: 20 };
    expect(adminUserKeys.list(params)).toEqual(['admin', 'users', 'list', params]);
  });
});

describe('useAdminUsers', () => {
  it('passes query, role and pagination to the client and returns the page', async () => {
    vi.mocked(adminUsersApi.listUsers).mockResolvedValue({ data: EMPTY_PAGE } as Awaited<
      ReturnType<typeof adminUsersApi.listUsers>
    >);

    const { result } = renderHook(
      () => useAdminUsers({ q: 'ali', role: 'MEMBER', page: 0, size: 20 }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(adminUsersApi.listUsers).toHaveBeenCalledWith({
      q: 'ali',
      role: 'MEMBER',
      page: 0,
      size: 20,
    });
    expect(result.current.data?.total).toBe(0);
  });

  it('sends an empty query as undefined', async () => {
    vi.mocked(adminUsersApi.listUsers).mockResolvedValue({ data: EMPTY_PAGE } as Awaited<
      ReturnType<typeof adminUsersApi.listUsers>
    >);

    const { result } = renderHook(() => useAdminUsers({ q: '', page: 0, size: 20 }), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(adminUsersApi.listUsers).toHaveBeenCalledWith({
      q: undefined,
      role: undefined,
      page: 0,
      size: 20,
    });
  });
});

describe('useCreateUser', () => {
  it('forwards the create request to the client', async () => {
    vi.mocked(adminUsersApi.createUser).mockResolvedValue({
      data: {},
    } as Awaited<ReturnType<typeof adminUsersApi.createUser>>);

    const { result } = renderHook(() => useCreateUser(), { wrapper });
    await result.current.mutateAsync({
      displayName: 'A',
      username: 'a',
      email: 'a@example.com',
      role: 'MEMBER',
    });

    expect(adminUsersApi.createUser).toHaveBeenCalledWith({
      adminUserCreateRequest: {
        displayName: 'A',
        username: 'a',
        email: 'a@example.com',
        role: 'MEMBER',
      },
    });
  });
});

describe('useUpdateUser', () => {
  it('forwards the id and patch to the client', async () => {
    vi.mocked(adminUsersApi.updateUser).mockResolvedValue({
      data: {},
    } as Awaited<ReturnType<typeof adminUsersApi.updateUser>>);

    const { result } = renderHook(() => useUpdateUser(), { wrapper });
    await result.current.mutateAsync({ id: 'u1', request: { role: 'AUDITOR' } });

    expect(adminUsersApi.updateUser).toHaveBeenCalledWith({
      userId: 'u1',
      adminUserUpdateRequest: { role: 'AUDITOR' },
    });
  });
});

describe('useSendUserPasswordReset', () => {
  it('sends a reset for the given id', async () => {
    vi.mocked(adminUsersApi.sendUserPasswordReset).mockResolvedValue({
      data: undefined,
    } as Awaited<ReturnType<typeof adminUsersApi.sendUserPasswordReset>>);

    const { result } = renderHook(() => useSendUserPasswordReset(), { wrapper });
    await result.current.mutateAsync('u9');

    expect(adminUsersApi.sendUserPasswordReset).toHaveBeenCalledWith({ userId: 'u9' });
  });
});
