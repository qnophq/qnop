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

import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import LinearProgress from '@mui/material/LinearProgress';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { ParseKeys } from 'i18next';
import { FlaskConical, ListChecks, Power, TriangleAlert } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { SchedulerJob } from '../../api/generated';
import {
  useRunSchedulerJob,
  useSchedulerJobs,
  useUpdateSchedulerJob,
} from '../../api/hooks/useAdminScheduler';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import { SchedulerJobCard } from '../../components/admin/scheduler/SchedulerJobCard';
import { StatStrip, type StatTile } from '../../components/dashboard/StatStrip';
import { apiErrorMessage } from '../../utils/apiError';

/**
 * Admin maintenance-scheduler dashboard (issue #524, ADR-0045): the fixed set of
 * background sweeps, each with its cron, enabled switch, dry-run toggle (reaper
 * only), a run-now button and the last-run outcome. Enabling/disabling and
 * run-now go straight to the SYSTEM audit stream.
 */
export function SchedulerPage() {
  const { t } = useTranslation(['admin', 'common']);
  const { data, isLoading, isFetching, isError } = useSchedulerJobs();
  const updateJob = useUpdateSchedulerJob();
  const runJob = useRunSchedulerJob();
  const { toast, notify, clear } = useToast();

  const [savingId, setSavingId] = useState<string | null>(null);
  const [runningId, setRunningId] = useState<string | null>(null);

  const jobs = data?.items ?? [];
  // Optimistic while the list is still loading, so the button does not appear a
  // beat late on the deployments — the majority — that allow manual runs.
  const manualRunEnabled = data?.manualRunEnabled ?? true;
  const jobSettingsEditable = data?.jobSettingsEditable ?? true;
  const tiles: StatTile[] = [
    { label: t('scheduler.tiles.jobs'), value: jobs.length, icon: ListChecks },
    {
      label: t('scheduler.tiles.enabled'),
      value: jobs.filter((j) => j.enabled).length,
      icon: Power,
      tone: 'success',
    },
    {
      label: t('scheduler.tiles.dryRun'),
      value: jobs.filter((j) => j.dryRun).length,
      icon: FlaskConical,
      tone: 'accent',
    },
    {
      label: t('scheduler.tiles.failing'),
      value: jobs.filter((j) => j.lastOutcome === 'FAILURE').length,
      icon: TriangleAlert,
      tone: 'danger',
    },
  ];

  const applyUpdate = async (
    job: SchedulerJob,
    change: { enabled?: boolean; dryRun?: boolean },
    message: string,
  ) => {
    setSavingId(job.jobId);
    try {
      await updateJob.mutateAsync({ jobId: job.jobId, ...change });
      notify(message);
    } catch (err) {
      notify(apiErrorMessage(err, t('scheduler.toast.saveFailed')), 'error');
    } finally {
      setSavingId(null);
    }
  };

  const onToggleEnabled = (job: SchedulerJob) =>
    applyUpdate(
      job,
      { enabled: !job.enabled },
      t(job.enabled ? 'scheduler.toast.disabled' : 'scheduler.toast.enabled', {
        job: job.displayName,
      }),
    );

  const onToggleDryRun = (job: SchedulerJob) =>
    applyUpdate(
      job,
      { dryRun: !job.dryRun },
      t(job.dryRun ? 'scheduler.toast.dryRunOff' : 'scheduler.toast.dryRunOn', {
        job: job.displayName,
      }),
    );

  const onRunNow = async (job: SchedulerJob) => {
    setRunningId(job.jobId);
    try {
      const result = await runJob.mutateAsync(job.jobId);
      if (result.lastOutcome === 'FAILURE') {
        notify(t('scheduler.toast.ranWithFailure', { job: job.displayName }), 'error');
      } else {
        notify(t('scheduler.toast.ran', { job: job.displayName }));
      }
    } catch (err) {
      notify(apiErrorMessage(err, t('scheduler.toast.startFailed')), 'error');
    } finally {
      setRunningId(null);
    }
  };

  // What this deployment withholds, in one sentence rather than two stacked
  // alerts — an operator who fixed both settings made a single decision.
  const notice: ParseKeys<'admin'> | null = manualRunEnabled
    ? jobSettingsEditable
      ? null
      : 'scheduler.notice.settingsFixed'
    : jobSettingsEditable
      ? 'scheduler.notice.manualRunDisabled'
      : 'scheduler.notice.managed';

  return (
    <Stack spacing={3}>
      <PageHeader title={t('scheduler.title')} description={t('scheduler.description')} />

      {!isError && jobs.length > 0 && <StatStrip tiles={tiles} />}

      {/* Said once, at the top, rather than as a tooltip on every inert control.
          The closing sentence is the one that matters: without it, a page of
          missing buttons and dead switches reads as a broken scheduler. */}
      {notice && !isError && (
        <Alert severity="info" variant="outlined">
          {t(notice)}
        </Alert>
      )}

      <Box sx={{ height: 3 }}>{isFetching && !isLoading && <LinearProgress />}</Box>

      {isError ? (
        <Alert severity="error">{t('scheduler.loadError')}</Alert>
      ) : isLoading ? (
        <Typography color="text.secondary" sx={{ fontSize: 14 }}>
          {t('common:loading')}
        </Typography>
      ) : (
        <Stack spacing={1.5}>
          {jobs.map((job) => (
            <SchedulerJobCard
              key={job.jobId}
              job={job}
              saving={savingId === job.jobId}
              running={runningId === job.jobId}
              onToggleEnabled={onToggleEnabled}
              onToggleDryRun={onToggleDryRun}
              onRunNow={onRunNow}
              manualRunEnabled={manualRunEnabled}
              jobSettingsEditable={jobSettingsEditable}
            />
          ))}
        </Stack>
      )}

      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
