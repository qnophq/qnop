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
import type { AdminSettingsResponse } from '../generated';
import { adminSettingsApi } from '../config';
import { bannerKeys } from './useBanner';
import { configKeys } from './useConfig';

export const settingsKeys = {
  all: ['admin', 'settings'] as const,
};

/**
 * The full set of application settings. Window-focus refetch is disabled so a
 * background refetch never clobbers a half-edited form; the list refreshes on
 * mount and after a save (which invalidates this query).
 */
export function useSettings() {
  return useQuery<AdminSettingsResponse>({
    queryKey: settingsKeys.all,
    queryFn: async () => {
      const response = await adminSettingsApi.getAdminSettings();
      return response.data;
    },
    refetchOnWindowFocus: false,
  });
}

/** Applies a partial map of setting key → raw value, then refreshes the settings. */
export function useUpdateSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (values: Record<string, string>) => {
      const response = await adminSettingsApi.updateAdminSettings({
        adminSettingsUpdateRequest: { values },
      });
      return response.data;
    },
    onSuccess: (data) => {
      // Seed the cache with the authoritative response so the form re-bases on
      // the freshly stored (and re-masked) values without a second round-trip.
      queryClient.setQueryData(settingsKeys.all, data);
      // Some settings are read through their own endpoints, and those caches
      // know nothing about this save (issue #664): the in-app banner comes from
      // /banner, which is otherwise only polled, and the sign-in banner from
      // /config. Without this, an admin switches a banner on and waits minutes —
      // or reloads — to see whether it worked, which is no way to check your own
      // wording. Invalidated unconditionally: this map is a partial patch, and
      // deciding "did a banner key change" from it would be one more thing that
      // can silently fall out of step with the registry.
      queryClient.invalidateQueries({ queryKey: bannerKeys.all });
      queryClient.invalidateQueries({ queryKey: configKeys.all });
    },
  });
}
