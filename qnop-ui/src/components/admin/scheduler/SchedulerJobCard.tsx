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
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Divider from '@mui/material/Divider';
import FormControlLabel from '@mui/material/FormControlLabel';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import type { ParseKeys } from 'i18next';
import { CheckCircle2, MinusCircle, Play, XCircle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { SchedulerJob } from '../../../api/generated';
import { useFormatters } from '../../../hooks/useFormatters';
import { ToneBadge, type BadgeTone } from '../ToneBadge';

interface SchedulerJobCardProps {
  job: SchedulerJob;
  /** True while this job's toggle mutation is in flight. */
  saving: boolean;
  /** True while this job's run-now is in flight. */
  running: boolean;
  onToggleEnabled: (job: SchedulerJob) => void;
  onToggleDryRun: (job: SchedulerJob) => void;
  onRunNow: (job: SchedulerJob) => void;
  /**
   * Whether this deployment allows a manual run at all (issue #676). False drops
   * the button rather than disabling it: a greyed-out control invites hovering
   * for a tooltip that explains nothing the page has not already said above.
   */
  manualRunEnabled: boolean;
  /**
   * Whether this deployment allows the enabled and dry-run switches to be
   * changed (issue #677). False renders them read-only rather than removing
   * them: unlike the run button, a switch carries information — whether the job
   * is on — that stays worth reading when it cannot be flipped.
   */
  jobSettingsEditable: boolean;
}

/**
 * The last-run outcome as a coloured pill: green success, red failure, neutral "never run".
 * The label is an `admin` catalog key, translated at render.
 */
function outcomeBadge(job: SchedulerJob): {
  tone: BadgeTone;
  labelKey: ParseKeys<'admin'>;
  icon: ReactNode;
} {
  if (job.lastOutcome === 'SUCCESS') {
    return {
      tone: 'green',
      labelKey: 'scheduler.card.outcome.success',
      icon: <CheckCircle2 size={13} />,
    };
  }
  if (job.lastOutcome === 'FAILURE') {
    return { tone: 'red', labelKey: 'scheduler.card.outcome.failed', icon: <XCircle size={13} /> };
  }
  return {
    tone: 'neutral',
    labelKey: 'scheduler.card.outcome.neverRun',
    icon: <MinusCircle size={13} />,
  };
}

/**
 * One maintenance sweep as a self-contained control card (issue #524): its name,
 * purpose and cron, an enabled switch, an optional dry-run switch (reaper only),
 * a run-now button, and the last-run outcome. Purely presentational — the page
 * owns the mutations and toasts.
 */
export function SchedulerJobCard({
  job,
  saving,
  running,
  onToggleEnabled,
  onToggleDryRun,
  onRunNow,
  manualRunEnabled,
  jobSettingsEditable,
}: SchedulerJobCardProps) {
  const { t } = useTranslation('admin');
  const theme = useTheme();
  const { formatRelative } = useFormatters();
  const badge = outcomeBadge(job);

  return (
    <Paper
      variant="outlined"
      sx={{
        p: { xs: 2, sm: 2.5 },
        borderRadius: '14px',
        opacity: job.enabled ? 1 : 0.72,
        transition: 'opacity 150ms',
      }}
    >
      <Stack
        direction={{ xs: 'column', md: 'row' }}
        spacing={2}
        sx={{ justifyContent: 'space-between', alignItems: { md: 'flex-start' } }}
      >
        {/* Identity: name, cron chip, purpose. */}
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <Typography variant="h2" sx={{ fontSize: 16 }}>
              {job.displayName}
            </Typography>
            <Box
              component="code"
              sx={{
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                fontSize: 12,
                px: 0.75,
                py: 0.25,
                borderRadius: 1,
                bgcolor: theme.qnop.surface2,
                color: 'text.secondary',
                border: '1px solid',
                borderColor: 'divider',
              }}
            >
              {job.cron}
            </Box>
          </Stack>
          <Typography color="text.secondary" sx={{ fontSize: 13.5, mt: 0.5 }}>
            {job.description}
          </Typography>
          <Stack
            direction="row"
            spacing={1}
            sx={{ alignItems: 'center', mt: 1.25, flexWrap: 'wrap', rowGap: 0.5 }}
          >
            <ToneBadge tone={badge.tone} label={t(badge.labelKey)} icon={badge.icon} />
            {job.lastRunAt && (
              <Typography color="text.secondary" sx={{ fontSize: 12.5 }}>
                {t(
                  job.lastTrigger === 'MANUAL'
                    ? 'scheduler.card.ranManually'
                    : 'scheduler.card.lastRun',
                  {
                    when: formatRelative(job.lastRunAt),
                  },
                )}
              </Typography>
            )}
            {job.lastDetail && (
              // The run's own report (issue #577 follow-up): an error summary in
              // red mono, a success summary ("Purged 2 review(s)…") as prose.
              <Typography
                color={job.lastOutcome === 'FAILURE' ? 'error.main' : 'text.secondary'}
                sx={{
                  fontSize: 12.5,
                  fontFamily: job.lastOutcome === 'FAILURE' ? 'ui-monospace, monospace' : undefined,
                }}
                title={job.lastDetail}
              >
                {job.lastOutcome === 'FAILURE' ? job.lastDetail : `— ${job.lastDetail}`}
              </Typography>
            )}
          </Stack>
        </Box>

        {/* Controls: enabled, optional dry-run, run-now. */}
        <Stack
          direction={{ xs: 'row', md: 'column' }}
          spacing={{ xs: 2, md: 1 }}
          sx={{ alignItems: { xs: 'center', md: 'flex-end' }, flexShrink: 0, flexWrap: 'wrap' }}
          divider={<Divider orientation="vertical" flexItem sx={{ display: { md: 'none' } }} />}
        >
          <Stack
            direction="column"
            spacing={0}
            sx={{ alignItems: { xs: 'flex-start', md: 'flex-end' } }}
          >
            <FormControlLabel
              sx={{ m: 0 }}
              labelPlacement="start"
              control={
                <Switch
                  size="small"
                  checked={job.enabled}
                  disabled={saving || !jobSettingsEditable}
                  onChange={() => onToggleEnabled(job)}
                  slotProps={{
                    input: {
                      'aria-label': t('scheduler.card.enableAria', { job: job.displayName }),
                    },
                  }}
                />
              }
              label={
                <Typography sx={{ fontSize: 13, mr: 1 }}>
                  {job.enabled ? t('scheduler.card.enabled') : t('scheduler.card.disabled')}
                </Typography>
              }
            />
            {job.supportsDryRun && (
              <FormControlLabel
                sx={{ m: 0 }}
                labelPlacement="start"
                control={
                  <Switch
                    size="small"
                    checked={job.dryRun}
                    disabled={saving || !jobSettingsEditable}
                    onChange={() => onToggleDryRun(job)}
                    slotProps={{
                      input: {
                        'aria-label': t('scheduler.card.dryRunAria', { job: job.displayName }),
                      },
                    }}
                  />
                }
                label={
                  <Typography sx={{ fontSize: 13, mr: 1 }}>{t('scheduler.card.dryRun')}</Typography>
                }
              />
            )}
          </Stack>
          {manualRunEnabled && (
            <Button
              size="small"
              variant="outlined"
              disabled={running}
              startIcon={
                running ? <CircularProgress size={14} color="inherit" /> : <Play size={15} />
              }
              onClick={() => onRunNow(job)}
            >
              {t('scheduler.card.runNow')}
            </Button>
          )}
        </Stack>
      </Stack>
    </Paper>
  );
}
