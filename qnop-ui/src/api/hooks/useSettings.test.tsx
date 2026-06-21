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
import type { AdminSettingsResponse } from '../generated';
import { settingsKeys, useSettings, useUpdateSettings } from './useSettings';
import { adminSettingsApi } from '../config';

const SETTINGS: AdminSettingsResponse = {
  settings: [
    {
      key: 'general.application_name',
      value: 'qnop',
      type: 'STRING',
      description: 'Name',
      sensitive: false,
    },
    {
      key: 'smtp.password',
      value: '***',
      type: 'PASSWORD',
      description: 'Secret',
      sensitive: true,
    },
  ],
};

vi.mock('../config', () => ({
  adminSettingsApi: {
    getAdminSettings: vi.fn(),
    updateAdminSettings: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('settingsKeys', () => {
  it('namespaces the settings query', () => {
    expect(settingsKeys.all).toEqual(['admin', 'settings']);
  });
});

describe('useSettings', () => {
  it('fetches and returns the settings list', async () => {
    vi.mocked(adminSettingsApi.getAdminSettings).mockResolvedValue({ data: SETTINGS } as Awaited<
      ReturnType<typeof adminSettingsApi.getAdminSettings>
    >);

    const { result } = renderHook(() => useSettings(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(adminSettingsApi.getAdminSettings).toHaveBeenCalled();
    expect(result.current.data?.settings).toHaveLength(2);
  });
});

describe('useUpdateSettings', () => {
  it('wraps the changed values in an update request', async () => {
    vi.mocked(adminSettingsApi.updateAdminSettings).mockResolvedValue({ data: SETTINGS } as Awaited<
      ReturnType<typeof adminSettingsApi.updateAdminSettings>
    >);

    const { result } = renderHook(() => useUpdateSettings(), { wrapper });
    await result.current.mutateAsync({ 'general.application_name': 'Acme' });

    expect(adminSettingsApi.updateAdminSettings).toHaveBeenCalledWith({
      adminSettingsUpdateRequest: { values: { 'general.application_name': 'Acme' } },
    });
  });
});
