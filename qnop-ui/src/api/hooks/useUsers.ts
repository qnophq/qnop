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
import type { PublicUserProfile } from '../generated';
import { usersApi } from '../config';

const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const userKeys = {
  publicProfile: (idOrSlug: string) => ['users', 'public-profile', idOrSlug] as const,
};

/**
 * A colleague's workspace-public profile (issues #454, #473, #486), shared by
 * the full profile page and the hover card so both dedupe on one cache entry.
 * The segment may be a user id or the profile slug (the ReviewParamGate
 * convention): UUID-shaped input resolves by id, anything else via the
 * by-slug endpoint. The 30s freshness keeps hover→page (and the UUID→slug
 * canonicalisation) fetch-free.
 */
export function useUserProfile(idOrSlug: string, enabled = true) {
  return useQuery<PublicUserProfile>({
    queryKey: userKeys.publicProfile(idOrSlug),
    queryFn: async () => {
      const response = UUID_SHAPE.test(idOrSlug)
        ? await usersApi.getUserProfile({ userId: idOrSlug })
        : await usersApi.getUserProfileBySlug({ slug: idOrSlug });
      return response.data;
    },
    enabled: enabled && idOrSlug !== '',
    staleTime: 30_000,
  });
}
