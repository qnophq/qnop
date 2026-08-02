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
import type { ConfigurationResponse, InstanceLimitsResponse } from '../generated';
import { adminConfigurationApi } from '../config';

export const adminConfigurationKeys = {
  all: ['admin', 'configuration'] as const,
  limits: ['admin', 'limits'] as const,
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

/**
 * The instance quotas and what they hold (issue #673).
 *
 * <p>Unlike the configuration beside it, usage moves while the page is open — somebody is adding
 * users, reviews are being finished — so this one is not cached indefinitely. It is still only
 * refetched on mount and focus: an administrator wants to know whether there is room, not to watch
 * a live counter.
 */
export function useInstanceLimits() {
  return useQuery<InstanceLimitsResponse>({
    queryKey: adminConfigurationKeys.limits,
    queryFn: async () => {
      const response = await adminConfigurationApi.getInstanceLimits();
      return response.data;
    },
    staleTime: 30_000,
  });
}
