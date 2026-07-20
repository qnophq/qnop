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
import type { OrphanDeleteResponse, StorageConsistencyReport } from '../generated';
import { adminStorageConsistencyApi } from '../config';

export const storageConsistencyKeys = {
  all: ['admin', 'storage-consistency'] as const,
  report: () => [...storageConsistencyKeys.all, 'report'] as const,
};

/**
 * Runs the storage-consistency scan (issue #523). The scan lists the whole
 * bucket, so it is deliberately not refetched on window focus; the page offers
 * an explicit rescan. A 409 (scan-limit circuit breaker) surfaces as the query
 * error for the page to render as a warning.
 */
export function useStorageConsistency() {
  return useQuery<StorageConsistencyReport>({
    queryKey: storageConsistencyKeys.report(),
    queryFn: async () => {
      const response = await adminStorageConsistencyApi.scanStorageConsistency();
      return response.data;
    },
    refetchOnWindowFocus: false,
    retry: false,
  });
}

/** Deletes orphaned objects (single or bulk), then refreshes the report. */
export function useDeleteOrphans() {
  const queryClient = useQueryClient();
  return useMutation<OrphanDeleteResponse, unknown, string[]>({
    mutationFn: async (keys: string[]) => {
      const response = await adminStorageConsistencyApi.deleteOrphanedObjects({
        orphanDeleteRequest: { keys },
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: storageConsistencyKeys.all }),
  });
}
