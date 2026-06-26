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
import type { ServerConfigResponse } from '../generated';
import { serverConfigApi } from '../config';

export const configKeys = {
  all: ['config'] as const,
};

/** Short staleness window so branding changes propagate across sessions (issue #154). */
const CONFIG_STALE_MS = 5 * 60_000;

/**
 * Public server configuration (edition, branding, OIDC providers, self-
 * registration flag, upload limits, supported formats). Cached with a short
 * staleness window and refetched on window focus, so an updated branding asset
 * (issue #154) propagates to other sessions without a manual reload; the admin
 * upload also invalidates this query for same-session immediacy.
 */
export function useConfig() {
  return useQuery<ServerConfigResponse>({
    queryKey: configKeys.all,
    queryFn: async () => {
      const response = await serverConfigApi.getServerConfig();
      return response.data;
    },
    staleTime: CONFIG_STALE_MS,
    refetchOnWindowFocus: true,
  });
}
