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
import { CheckCircle2, MinusCircle, Play, XCircle } from 'lucide-react';
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
}

/** The last-run outcome as a coloured pill: green success, red failure, neutral "never run". */
function outcomeBadge(job: SchedulerJob) {
  if (job.lastOutcome === 'SUCCESS') {
    return { tone: 'green' as BadgeTone, label: 'Success', icon: <CheckCircle2 size={13} /> };
  }
  if (job.lastOutcome === 'FAILURE') {
    return { tone: 'red' as BadgeTone, label: 'Failed', icon: <XCircle size={13} /> };
  }
  return { tone: 'neutral' as BadgeTone, label: 'Never run', icon: <MinusCircle size={13} /> };
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
}: SchedulerJobCardProps) {
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
            <ToneBadge tone={badge.tone} label={badge.label} icon={badge.icon} />
            {job.lastRunAt && (
              <Typography color="text.secondary" sx={{ fontSize: 12.5 }}>
                {job.lastTrigger === 'MANUAL' ? 'Ran manually' : 'Last run'}{' '}
                {formatRelative(job.lastRunAt)}
              </Typography>
            )}
            {job.lastOutcome === 'FAILURE' && job.lastDetail && (
              <Typography
                color="error.main"
                sx={{ fontSize: 12.5, fontFamily: 'ui-monospace, monospace' }}
                title={job.lastDetail}
              >
                {job.lastDetail}
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
                  disabled={saving}
                  onChange={() => onToggleEnabled(job)}
                  slotProps={{ input: { 'aria-label': `Enable ${job.displayName}` } }}
                />
              }
              label={
                <Typography sx={{ fontSize: 13, mr: 1 }}>
                  {job.enabled ? 'Enabled' : 'Disabled'}
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
                    disabled={saving}
                    onChange={() => onToggleDryRun(job)}
                    slotProps={{ input: { 'aria-label': `Dry-run ${job.displayName}` } }}
                  />
                }
                label={<Typography sx={{ fontSize: 13, mr: 1 }}>Dry run</Typography>}
              />
            )}
          </Stack>
          <Button
            size="small"
            variant="outlined"
            disabled={running}
            startIcon={
              running ? <CircularProgress size={14} color="inherit" /> : <Play size={15} />
            }
            onClick={() => onRunNow(job)}
          >
            Run now
          </Button>
        </Stack>
      </Stack>
    </Paper>
  );
}
