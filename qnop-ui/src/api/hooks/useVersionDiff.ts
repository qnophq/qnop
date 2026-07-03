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
import { isAxiosError } from 'axios';
import type { VersionDiffResponse } from '../generated';
import { documentsApi } from '../config';
import { apiErrorCode } from '../../utils/apiError';

export const versionDiffKeys = {
  pair: (documentId: string, from: number, to: number) =>
    ['documents', 'diff', documentId, from, to] as const,
};

/**
 * The located changes between two versions (ADR-0034). Versions are immutable,
 * so a pair's diff never changes: `staleTime: Infinity`, no refetching. The
 * server answers 409 while extraction of either side is still PENDING (or
 * FAILED) — surfaced via {@link versionDiffErrorCode}, not retried (the version
 * poller drives when to re-enable the query).
 */
export function useVersionDiff(documentId: string, from?: number, to?: number) {
  const validPair = from !== undefined && to !== undefined && from !== to;
  return useQuery<VersionDiffResponse>({
    queryKey: versionDiffKeys.pair(documentId, from ?? 0, to ?? 0),
    queryFn: async () => {
      const response = await documentsApi.getVersionDiff({
        documentId,
        from: from as number,
        to: to as number,
      });
      return response.data;
    },
    enabled: documentId.length > 0 && validPair,
    staleTime: Infinity,
    retry: (failureCount, error) => {
      // 4xx/409 are stable answers for this pair — retrying cannot help.
      if (isAxiosError(error) && error.response && error.response.status < 500) return false;
      return failureCount < 2;
    },
  });
}

/** The stable error code of a failed diff query (`EXTRACTION_PENDING`, …). */
export function versionDiffErrorCode(error: unknown): string | undefined {
  return apiErrorCode(error);
}
