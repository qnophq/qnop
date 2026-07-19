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
import type { SchedulerJob, SchedulerJobListResponse } from '../generated';
import { adminSchedulerApi } from '../config';

export const schedulerKeys = {
  all: ['admin', 'scheduler'] as const,
};

/**
 * The catalogue of maintenance sweeps with their operator state (issue #524).
 * Window-focus refetch is off so a background refetch never clobbers an
 * in-flight toggle; the list refreshes on mount and after each mutation.
 */
export function useSchedulerJobs() {
  return useQuery<SchedulerJobListResponse>({
    queryKey: schedulerKeys.all,
    queryFn: async () => {
      const response = await adminSchedulerApi.listSchedulerJobs();
      return response.data;
    },
    refetchOnWindowFocus: false,
  });
}

/** Merges one updated job back into the cached list without a round-trip. */
function replaceJob(previous: SchedulerJobListResponse | undefined, job: SchedulerJob) {
  if (!previous) {
    return previous;
  }
  return {
    ...previous,
    items: previous.items.map((item) => (item.jobId === job.jobId ? job : item)),
  };
}

/** Enables/disables a sweep or toggles its dry-run mode; a null field is unchanged. */
export function useUpdateSchedulerJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { jobId: string; enabled?: boolean; dryRun?: boolean }) => {
      const response = await adminSchedulerApi.updateSchedulerJob({
        jobId: input.jobId,
        schedulerJobUpdateRequest: { enabled: input.enabled, dryRun: input.dryRun },
      });
      return response.data;
    },
    onSuccess: (job) => {
      queryClient.setQueryData<SchedulerJobListResponse>(schedulerKeys.all, (previous) =>
        replaceJob(previous, job),
      );
    },
  });
}

/** Triggers an immediate run; the returned job carries its refreshed last-run outcome. */
export function useRunSchedulerJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (jobId: string) => {
      const response = await adminSchedulerApi.runSchedulerJob({ jobId });
      return response.data;
    },
    onSuccess: (job) => {
      queryClient.setQueryData<SchedulerJobListResponse>(schedulerKeys.all, (previous) =>
        replaceJob(previous, job),
      );
    },
  });
}
