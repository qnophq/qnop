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
import type { ConfigurationResponse } from '../generated';
import { adminConfigurationApi } from '../config';

export const adminConfigurationKeys = {
  all: ['admin', 'configuration'] as const,
};

/**
 * The effective `qnop.*` configuration the server bound at startup (issue #522). Read-only and
 * rarely changing (it only moves on a redeploy), so the cache is held long — a manual refetch is
 * never the point. Secrets are already redacted server-side, so the payload is safe to cache.
 */
export function useAdminConfiguration() {
  return useQuery<ConfigurationResponse>({
    queryKey: adminConfigurationKeys.all,
    queryFn: async () => {
      const response = await adminConfigurationApi.getAdminConfiguration();
      return response.data;
    },
    staleTime: Infinity,
  });
}
