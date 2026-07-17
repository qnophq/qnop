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

import { useQuery } from '@tanstack/react-query';
import type { UserSettingsResponse } from '../generated';
import { userSettingsApi } from '../config';
import { useAuthStore } from '../../stores/authStore';

export const userSettingsKeys = {
  all: ['user', 'settings'] as const,
};

/**
 * The current user's per-user settings (theme, preferred language, timezone).
 * Gated on authentication: the endpoint is behind auth, so anonymous surfaces
 * (login, registration) never fire it — consumers fall back to the application
 * default there. Settings change rarely, so the cache is kept fresh for the
 * session rather than refetched on every window focus.
 */
export function useCurrentUserSettings() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  return useQuery<UserSettingsResponse>({
    queryKey: userSettingsKeys.all,
    queryFn: async () => {
      const response = await userSettingsApi.getCurrentUserSettings();
      return response.data;
    },
    enabled: isAuthenticated,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
}

/** The value of a single setting key, or {@code undefined} when unset/unloaded. */
export function useUserSettingValue(key: string): string | undefined {
  const { data } = useCurrentUserSettings();
  return data?.settings.find((setting) => setting.key === key)?.value;
}
