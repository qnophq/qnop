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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingValueType } from '../generated';
import { userSettingsApi } from '../config';
import {
  EMAIL_REVIEW_NOTIFICATIONS_KEY,
  useUpdateUserSettings,
  useUserSettings,
  userSettingKeys,
} from './useUserSettings';

vi.mock('../config', () => ({
  userSettingsApi: {
    getCurrentUserSettings: vi.fn(),
    updateCurrentUserSettings: vi.fn(),
  },
}));

// useUserSettings is gated on authentication (issue #465): the query only fires
// for a signed-in caller, so the tests run as one.
vi.mock('../../stores/authStore', () => ({
  useAuthStore: (selector: (s: { isAuthenticated: boolean }) => unknown) =>
    selector({ isAuthenticated: true }),
}));

const mockedApi = vi.mocked(userSettingsApi);

const response = (value: string) => ({
  data: {
    settings: [
      {
        key: EMAIL_REVIEW_NOTIFICATIONS_KEY,
        value,
        type: SettingValueType.Boolean,
        description: 'Receive email notifications for review activity.',
      },
    ],
  },
});

function harness() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return { queryClient, wrapper };
}

beforeEach(() => vi.clearAllMocks());

describe('useUserSettings (issue #316)', () => {
  it('loads the settings overlay', async () => {
    mockedApi.getCurrentUserSettings.mockResolvedValue(response('true') as never);
    const { wrapper } = harness();

    const { result } = renderHook(() => useUserSettings(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.settings[0]?.value).toBe('true');
  });

  it('flips the cached value optimistically and rolls back on failure', async () => {
    mockedApi.getCurrentUserSettings.mockResolvedValue(response('true') as never);
    let reject: (reason: Error) => void = () => undefined;
    mockedApi.updateCurrentUserSettings.mockReturnValue(
      new Promise((_resolve, rej) => {
        reject = rej;
      }) as never,
    );
    const { queryClient, wrapper } = harness();
    const settings = renderHook(() => useUserSettings(), { wrapper });
    await waitFor(() => expect(settings.result.current.isSuccess).toBe(true));

    const { result } = renderHook(() => useUpdateUserSettings(), { wrapper });
    result.current.mutate({ [EMAIL_REVIEW_NOTIFICATIONS_KEY]: 'false' });

    type Cached = { settings: { value?: string }[] };
    await waitFor(() =>
      expect(queryClient.getQueryData<Cached>(userSettingKeys.all)?.settings[0]?.value).toBe(
        'false',
      ),
    );

    reject(new Error('server refused'));
    await waitFor(() =>
      expect(queryClient.getQueryData<Cached>(userSettingKeys.all)?.settings[0]?.value).toBe(
        'true',
      ),
    );
  });
});
