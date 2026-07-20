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

import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { OrphanDeleteResponse, StorageConsistencyReport } from '../generated';
import { useDeleteOrphans, useStorageConsistency } from './useStorageConsistency';
import { adminStorageConsistencyApi } from '../config';

const EMPTY_REPORT: StorageConsistencyReport = {
  summary: {
    dbReferencedCount: 0,
    storageObjectCount: 0,
    missingCount: 0,
    orphanedCount: 0,
    scannedAt: '2026-07-19T10:00:00Z',
  },
  missing: [],
  orphaned: [],
};

const DELETE_RESULT: OrphanDeleteResponse = { deleted: ['k1'], skipped: [] };

vi.mock('../config', () => ({
  adminStorageConsistencyApi: {
    scanStorageConsistency: vi.fn(),
    deleteOrphanedObjects: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('useStorageConsistency', () => {
  it('returns the scan report', async () => {
    vi.mocked(adminStorageConsistencyApi.scanStorageConsistency).mockResolvedValue({
      data: EMPTY_REPORT,
    } as Awaited<ReturnType<typeof adminStorageConsistencyApi.scanStorageConsistency>>);

    const { result } = renderHook(() => useStorageConsistency(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.summary.dbReferencedCount).toBe(0);
  });
});

describe('useDeleteOrphans', () => {
  it('sends the selected keys in the request body', async () => {
    vi.mocked(adminStorageConsistencyApi.deleteOrphanedObjects).mockResolvedValue({
      data: DELETE_RESULT,
    } as Awaited<ReturnType<typeof adminStorageConsistencyApi.deleteOrphanedObjects>>);

    const { result } = renderHook(() => useDeleteOrphans(), { wrapper });
    result.current.mutate(['k1', 'k2']);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(adminStorageConsistencyApi.deleteOrphanedObjects).toHaveBeenCalledWith({
      orphanDeleteRequest: { keys: ['k1', 'k2'] },
    });
  });
});
