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
import type { SchedulerJob, SchedulerJobListResponse } from '../generated';
import {
  schedulerKeys,
  useRunSchedulerJob,
  useSchedulerJobs,
  useUpdateSchedulerJob,
} from './useAdminScheduler';
import { adminSchedulerApi } from '../config';

const REAPER: SchedulerJob = {
  jobId: 'storageOrphanReaper',
  displayName: 'Storage orphan reaper',
  description: 'Deletes uncommitted objects.',
  cron: '0 30 3 * * *',
  supportsDryRun: true,
  enabled: true,
  dryRun: false,
};

const LIST: SchedulerJobListResponse = { items: [REAPER] };

vi.mock('../config', () => ({
  adminSchedulerApi: {
    listSchedulerJobs: vi.fn(),
    updateSchedulerJob: vi.fn(),
    runSchedulerJob: vi.fn(),
  },
}));

function makeClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function wrapperWith(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('schedulerKeys', () => {
  it('namespaces the scheduler query', () => {
    expect(schedulerKeys.all).toEqual(['admin', 'scheduler']);
  });
});

describe('useSchedulerJobs', () => {
  it('fetches the catalogue', async () => {
    vi.mocked(adminSchedulerApi.listSchedulerJobs).mockResolvedValue({ data: LIST } as Awaited<
      ReturnType<typeof adminSchedulerApi.listSchedulerJobs>
    >);

    const { result } = renderHook(() => useSchedulerJobs(), { wrapper: wrapperWith(makeClient()) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items).toHaveLength(1);
  });
});

describe('useUpdateSchedulerJob', () => {
  it('sends the partial change and merges the result into the cached list', async () => {
    const updated: SchedulerJob = { ...REAPER, dryRun: true };
    vi.mocked(adminSchedulerApi.updateSchedulerJob).mockResolvedValue({
      data: updated,
    } as Awaited<ReturnType<typeof adminSchedulerApi.updateSchedulerJob>>);

    const queryClient = makeClient();
    queryClient.setQueryData(schedulerKeys.all, LIST);

    const { result } = renderHook(() => useUpdateSchedulerJob(), {
      wrapper: wrapperWith(queryClient),
    });
    await result.current.mutateAsync({ jobId: REAPER.jobId, dryRun: true });

    expect(adminSchedulerApi.updateSchedulerJob).toHaveBeenCalledWith({
      jobId: 'storageOrphanReaper',
      schedulerJobUpdateRequest: { enabled: undefined, dryRun: true },
    });
    const cached = queryClient.getQueryData<SchedulerJobListResponse>(schedulerKeys.all);
    expect(cached?.items[0].dryRun).toBe(true);
  });
});

describe('useRunSchedulerJob', () => {
  it('runs the job and refreshes its cached row with the outcome', async () => {
    const ran: SchedulerJob = { ...REAPER, lastOutcome: 'SUCCESS', lastTrigger: 'MANUAL' };
    vi.mocked(adminSchedulerApi.runSchedulerJob).mockResolvedValue({ data: ran } as Awaited<
      ReturnType<typeof adminSchedulerApi.runSchedulerJob>
    >);

    const queryClient = makeClient();
    queryClient.setQueryData(schedulerKeys.all, LIST);

    const { result } = renderHook(() => useRunSchedulerJob(), {
      wrapper: wrapperWith(queryClient),
    });
    await result.current.mutateAsync(REAPER.jobId);

    expect(adminSchedulerApi.runSchedulerJob).toHaveBeenCalledWith({
      jobId: 'storageOrphanReaper',
    });
    const cached = queryClient.getQueryData<SchedulerJobListResponse>(schedulerKeys.all);
    expect(cached?.items[0].lastOutcome).toBe('SUCCESS');
  });

  it('leaves an unseeded cache untouched', async () => {
    const ran: SchedulerJob = { ...REAPER, lastOutcome: 'SUCCESS' };
    vi.mocked(adminSchedulerApi.runSchedulerJob).mockResolvedValue({ data: ran } as Awaited<
      ReturnType<typeof adminSchedulerApi.runSchedulerJob>
    >);

    const queryClient = makeClient(); // no list cached yet
    const { result } = renderHook(() => useRunSchedulerJob(), {
      wrapper: wrapperWith(queryClient),
    });
    await result.current.mutateAsync(REAPER.jobId);

    expect(queryClient.getQueryData(schedulerKeys.all)).toBeUndefined();
  });
});
