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
import type { ConfigurationResponse } from '../generated';
import { adminConfigurationKeys, useAdminConfiguration } from './useAdminConfiguration';
import { adminConfigurationApi } from '../config';

const RESPONSE: ConfigurationResponse = {
  groups: [
    {
      key: 'auth',
      entries: [
        {
          path: 'qnop.auth.jwt-secret',
          envVar: 'QNOP_AUTH_JWT_SECRET',
          valueType: 'SECRET',
          configured: true,
        },
      ],
    },
  ],
};

vi.mock('../config', () => ({
  adminConfigurationApi: {
    getAdminConfiguration: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('adminConfigurationKeys', () => {
  it('namespaces the configuration query', () => {
    expect(adminConfigurationKeys.all).toEqual(['admin', 'configuration']);
  });
});

describe('useAdminConfiguration', () => {
  it('fetches and returns the grouped effective configuration', async () => {
    vi.mocked(adminConfigurationApi.getAdminConfiguration).mockResolvedValue({
      data: RESPONSE,
    } as Awaited<ReturnType<typeof adminConfigurationApi.getAdminConfiguration>>);

    const { result } = renderHook(() => useAdminConfiguration(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(adminConfigurationApi.getAdminConfiguration).toHaveBeenCalled();
    expect(result.current.data?.groups).toHaveLength(1);
    expect(result.current.data?.groups[0].key).toBe('auth');
  });
});
