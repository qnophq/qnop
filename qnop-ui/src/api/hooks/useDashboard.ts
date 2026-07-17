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
import type { DashboardResponse } from '../generated';
import { dashboardApi } from '../config';

export const dashboardKeys = {
  all: ['dashboard'] as const,
};

/**
 * The dashboard's cross-review aggregates (issue #454): replies directed at
 * the caller, recent activity, the weekly resolved count. Everything
 * per-review comes from {@link useReviews} — together the dashboard is two
 * requests, never a per-review fan-out.
 */
export function useDashboard() {
  return useQuery<DashboardResponse>({
    queryKey: dashboardKeys.all,
    queryFn: async () => {
      const response = await dashboardApi.getDashboard();
      return response.data;
    },
  });
}
