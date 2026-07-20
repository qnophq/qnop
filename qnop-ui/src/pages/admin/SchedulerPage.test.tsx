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
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SchedulerJob } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { SchedulerPage } from './SchedulerPage';

const { updateMutate, runMutate, useSchedulerJobsMock } = vi.hoisted(() => ({
  updateMutate: vi.fn(),
  runMutate: vi.fn(),
  useSchedulerJobsMock: vi.fn(),
}));

const REFRESH: SchedulerJob = {
  jobId: 'refreshTokenSweep',
  displayName: 'Refresh token sweep',
  description: 'Deletes expired refresh tokens.',
  cron: '0 40 3 * * *',
  supportsDryRun: false,
  enabled: true,
  dryRun: false,
};

const REAPER: SchedulerJob = {
  jobId: 'storageOrphanReaper',
  displayName: 'Storage orphan reaper',
  description: 'Deletes uncommitted objects.',
  cron: '0 30 3 * * *',
  supportsDryRun: true,
  enabled: true,
  dryRun: false,
};

vi.mock('../../api/hooks/useAdminScheduler', () => ({
  useSchedulerJobs: () => useSchedulerJobsMock(),
  useUpdateSchedulerJob: () => ({ mutateAsync: updateMutate }),
  useRunSchedulerJob: () => ({ mutateAsync: runMutate }),
}));

vi.mock('../../components/admin/scheduler/SchedulerJobCard', () => ({
  SchedulerJobCard: ({
    job,
    onToggleEnabled,
    onToggleDryRun,
    onRunNow,
  }: {
    job: SchedulerJob;
    onToggleEnabled: (j: SchedulerJob) => void;
    onToggleDryRun: (j: SchedulerJob) => void;
    onRunNow: (j: SchedulerJob) => void;
  }) => (
    <div>
      <span>{job.displayName}</span>
      <button onClick={() => onToggleEnabled(job)}>enable-{job.jobId}</button>
      <button onClick={() => onToggleDryRun(job)}>dryrun-{job.jobId}</button>
      <button onClick={() => onRunNow(job)}>run-{job.jobId}</button>
    </div>
  ),
}));

beforeEach(() => {
  updateMutate.mockReset().mockResolvedValue(undefined);
  runMutate.mockReset().mockResolvedValue({ ...REFRESH, lastOutcome: 'SUCCESS' });
  useSchedulerJobsMock.mockReset().mockReturnValue({
    data: { items: [REFRESH, REAPER] },
    isLoading: false,
    isFetching: false,
    isError: false,
  });
});

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const renderPage = () => render(<SchedulerPage />, { wrapper });

describe('SchedulerPage', () => {
  it('lists the catalogued jobs', () => {
    renderPage();
    expect(screen.getByText('Refresh token sweep')).toBeTruthy();
    expect(screen.getByText('Storage orphan reaper')).toBeTruthy();
  });

  it('shows a progress bar while refetching', () => {
    useSchedulerJobsMock.mockReturnValue({
      data: { items: [REFRESH, REAPER] },
      isLoading: false,
      isFetching: true,
      isError: false,
    });
    renderPage();
    expect(screen.getByRole('progressbar')).toBeTruthy();
  });

  it('shows an error alert when the jobs cannot be loaded', () => {
    useSchedulerJobsMock.mockReturnValue({
      data: undefined,
      isLoading: false,
      isFetching: false,
      isError: true,
    });
    renderPage();
    expect(screen.getByText('The scheduled jobs could not be loaded.')).toBeTruthy();
    expect(screen.queryByText('Refresh token sweep')).toBeNull();
  });

  it('disables a sweep and surfaces a success toast', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'enable-refreshTokenSweep' }));

    await waitFor(() =>
      expect(updateMutate).toHaveBeenCalledWith({ jobId: 'refreshTokenSweep', enabled: false }),
    );
    expect(await screen.findByText('Refresh token sweep disabled.')).toBeTruthy();
  });

  it('toggles the reaper dry-run and surfaces a success toast', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'dryrun-storageOrphanReaper' }));

    await waitFor(() =>
      expect(updateMutate).toHaveBeenCalledWith({ jobId: 'storageOrphanReaper', dryRun: true }),
    );
    expect(await screen.findByText('Dry run on for Storage orphan reaper.')).toBeTruthy();
  });

  it('runs a job and reports success', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'run-refreshTokenSweep' }));

    await waitFor(() => expect(runMutate).toHaveBeenCalledWith('refreshTokenSweep'));
    expect(await screen.findByText('Refresh token sweep ran.')).toBeTruthy();
  });

  it('reports a run that returns a FAILURE outcome as an error toast', async () => {
    runMutate.mockResolvedValue({ ...REFRESH, lastOutcome: 'FAILURE' });
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'run-refreshTokenSweep' }));

    const alert = await screen.findByText('Refresh token sweep ran but reported a failure.');
    expect(alert.closest('.MuiAlert-colorError')).toBeTruthy();
  });

  it('reports a run that throws as an error toast', async () => {
    runMutate.mockRejectedValue(new Error('boom'));
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'run-refreshTokenSweep' }));

    const alert = await screen.findByText('The job could not be started.');
    expect(alert.closest('.MuiAlert-colorError')).toBeTruthy();
  });
});
