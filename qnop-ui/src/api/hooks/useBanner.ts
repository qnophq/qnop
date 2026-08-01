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
import type { BannerResponse } from '../generated';
import { bannerApi } from '../config';

export const bannerKeys = {
  all: ['banner'] as const,
};

/**
 * How often to ask again. A maintenance notice has to reach a browser that has
 * been open since this morning, and the read costs the server nothing — the
 * settings snapshot is in memory (ADR-0025), so no database is touched.
 */
const BANNER_POLL_MS = 5 * 60_000;

/**
 * The operator's in-app notice (issue #664), or nothing.
 *
 * <p>Its own endpoint rather than a field on the public config: this one is
 * authenticated, because a deployment describing its own trouble should not be
 * describing it to anonymous callers.
 */
export function useBanner(enabled: boolean) {
  return useQuery<BannerResponse>({
    queryKey: bannerKeys.all,
    queryFn: async () => {
      const response = await bannerApi.getBanner();
      return response.data;
    },
    enabled,
    refetchInterval: BANNER_POLL_MS,
    refetchOnWindowFocus: true,
    staleTime: BANNER_POLL_MS,
  });
}
