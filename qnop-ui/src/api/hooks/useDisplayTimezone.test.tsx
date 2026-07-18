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
import { useDisplayTimezone } from './useDisplayTimezone';
import { serverConfigApi, userSettingsApi } from '../config';

vi.mock('../config', () => ({
  serverConfigApi: { getServerConfig: vi.fn() },
  userSettingsApi: { getCurrentUserSettings: vi.fn() },
}));

vi.mock('../../stores/authStore', () => ({
  useAuthStore: (selector: (s: { isAuthenticated: boolean }) => unknown) =>
    selector({ isAuthenticated: true }),
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

function mockConfig(defaultTimezone: string) {
  vi.mocked(serverConfigApi.getServerConfig).mockResolvedValue({
    data: { edition: 'COMMUNITY', general: { siteName: 'qnop', defaultTimezone } },
  } as Awaited<ReturnType<typeof serverConfigApi.getServerConfig>>);
}

function mockUserSettings(settings: Array<{ key: string; value: string }>) {
  vi.mocked(userSettingsApi.getCurrentUserSettings).mockResolvedValue({
    data: { settings },
  } as Awaited<ReturnType<typeof userSettingsApi.getCurrentUserSettings>>);
}

describe('useDisplayTimezone', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses the user profile timezone when set', async () => {
    mockConfig('Asia/Tokyo');
    mockUserSettings([{ key: 'timezone', value: 'Europe/Berlin' }]);

    const { result } = renderHook(() => useDisplayTimezone(), { wrapper });

    await waitFor(() => expect(result.current).toBe('Europe/Berlin'));
  });

  it('falls back to the application default when the user has no timezone', async () => {
    mockConfig('Asia/Tokyo');
    mockUserSettings([{ key: 'theme', value: 'dark' }]);

    const { result } = renderHook(() => useDisplayTimezone(), { wrapper });

    await waitFor(() => expect(result.current).toBe('Asia/Tokyo'));
  });

  it('falls back to UTC when neither user nor application default is valid', async () => {
    mockConfig('');
    mockUserSettings([]);

    const { result } = renderHook(() => useDisplayTimezone(), { wrapper });

    await waitFor(() => expect(result.current).toBe('UTC'));
  });
});
