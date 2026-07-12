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
import type { UserSettingsResponse } from '../generated';
import { userSettingsApi } from '../config';

/** The per-user opt-out for review activity mails (issue #316). */
export const EMAIL_REVIEW_NOTIFICATIONS_KEY = 'email_review_notifications';

export const userSettingKeys = {
  all: ['user-settings'] as const,
};

/** The caller's settings, stored values overlaid on registry defaults (issue #22). */
export function useUserSettings() {
  return useQuery<UserSettingsResponse>({
    queryKey: userSettingKeys.all,
    queryFn: async () => {
      const response = await userSettingsApi.getCurrentUserSettings();
      return response.data;
    },
  });
}

/**
 * Applies a partial settings change optimistically — a toggle must not lag —
 * and rolls back to the snapshot when the server refuses.
 */
export function useUpdateUserSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (values: Record<string, string>) => {
      const response = await userSettingsApi.updateCurrentUserSettings({
        userSettingsUpdateRequest: { values },
      });
      return response.data;
    },
    onMutate: async (values) => {
      await queryClient.cancelQueries({ queryKey: userSettingKeys.all });
      const snapshot = queryClient.getQueryData<UserSettingsResponse>(userSettingKeys.all);
      if (snapshot) {
        queryClient.setQueryData<UserSettingsResponse>(userSettingKeys.all, {
          ...snapshot,
          settings: snapshot.settings.map((setting) =>
            values[setting.key] !== undefined
              ? { ...setting, value: values[setting.key] }
              : setting,
          ),
        });
      }
      return { snapshot };
    },
    onError: (_error, _values, context) => {
      if (context?.snapshot) {
        queryClient.setQueryData(userSettingKeys.all, context.snapshot);
      }
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: userSettingKeys.all }),
  });
}
