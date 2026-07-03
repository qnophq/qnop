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
import Divider from '@mui/material/Divider';
import FormControlLabel from '@mui/material/FormControlLabel';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { ArrowLeftRight, MoveRight } from 'lucide-react';
import type { DiffChange, DocumentVersionSummary } from '../../../api/generated';
import { ExtractionStatus } from '../../../api/generated';
import { tokens } from '../../../theme/tokens';
import { ZoomControls } from '../viewer/ZoomControls';
import { diffStats } from './diffModel';

interface CompareToolbarProps {
  versions: DocumentVersionSummary[];
  from: number;
  to: number;
  onChangePair: (from: number, to: number) => void;
  syncScroll: boolean;
  onSyncScrollChange: (value: boolean) => void;
  /** The diff's changes; null while the comparison is still loading. */
  changes: DiffChange[] | null;
  /** Shared fit-width zoom of both panes (1 = fit width). */
  zoom: number;
  onZoomChange: (zoom: number) => void;
}

/** One compact statistic in the control strip: a tiny label over a mono value. */
function Stat({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <Box sx={{ textAlign: 'left' }}>
      <Typography sx={{ fontSize: 10, lineHeight: 1.3, color: 'text.secondary' }}>
        {label}
      </Typography>
      <Typography
        sx={{
          fontFamily: tokens.font.mono,
          fontSize: 12.5,
          fontWeight: 600,
          lineHeight: 1.2,
          fontVariantNumeric: 'tabular-nums',
          color: color ?? 'text.primary',
          whiteSpace: 'nowrap',
        }}
      >
        {value}
      </Typography>
    </Box>
  );
}

/**
 * The comparison's control strip: the baseline/compare version pickers (only
 * extracted versions are selectable, the two sides never collapse onto the
 * same version), a swap button, the diff statistics (±words, changes, pages),
 * the shared zoom and the sync-scroll toggle.
 */
export function CompareToolbar({
  versions,
  from,
  to,
  onChangePair,
  syncScroll,
  onSyncScrollChange,
  changes,
  zoom,
  onZoomChange,
}: CompareToolbarProps) {
  const theme = useTheme();
  const dark = theme.palette.mode === 'dark';
  const stats = changes ? diffStats(changes) : null;
  const pagesValue =
    stats === null
      ? ''
      : stats.pages.length === 0
        ? '—'
        : stats.pages.length > 3
          ? `${stats.pages.length} pages`
          : stats.pages.join(', ');
  const pickerItems = (other: number) =>
    versions.map((version) => (
      <MenuItem
        key={version.versionNumber}
        value={String(version.versionNumber)}
        disabled={
          version.extractionStatus !== ExtractionStatus.Ready || version.versionNumber === other
        }
      >
        v{version.versionNumber}
      </MenuItem>
    ));

  return (
    <Paper
      variant="outlined"
      sx={{ px: 1.5, py: 1, display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}
    >
      <TextField
        select
        size="small"
        label="Baseline"
        value={String(from)}
        onChange={(event) => onChangePair(Number(event.target.value), to)}
        sx={{ minWidth: 110 }}
      >
        {pickerItems(to)}
      </TextField>
      <MoveRight size={16} aria-hidden style={{ flexShrink: 0, opacity: 0.6 }} />
      <TextField
        select
        size="small"
        label="Compare"
        value={String(to)}
        onChange={(event) => onChangePair(from, Number(event.target.value))}
        sx={{ minWidth: 110 }}
      >
        {pickerItems(from)}
      </TextField>
      <Tooltip title="Swap sides">
        <IconButton
          size="small"
          aria-label="Swap the compared versions"
          onClick={() => onChangePair(to, from)}
        >
          <ArrowLeftRight size={15} />
        </IconButton>
      </Tooltip>

      {stats === null ? (
        <Typography variant="body2" color="text.secondary" aria-live="polite" sx={{ ml: 'auto' }}>
          Comparing…
        </Typography>
      ) : (
        <Stack
          direction="row"
          spacing={2}
          aria-live="polite"
          data-testid="toolbar-diff-stats"
          sx={{ ml: 'auto', alignItems: 'center' }}
        >
          <Stat
            label="Words +"
            value={`+${stats.addedWords}`}
            color={dark ? tokens.badge.green.fgDark : tokens.semantic.successStrong}
          />
          <Stat
            label="Words −"
            value={`−${stats.removedWords}`}
            color={dark ? tokens.badge.red.fgDark : tokens.semantic.dangerStrong}
          />
          <Stat label="Changes" value={String(changes?.length ?? 0)} />
          <Stat label={stats.pages.length === 1 ? 'Page' : 'Pages'} value={pagesValue} />
        </Stack>
      )}

      <Divider orientation="vertical" flexItem />

      <ZoomControls zoom={zoom} onZoomChange={onZoomChange} />

      <FormControlLabel
        control={
          <Switch
            size="small"
            checked={syncScroll}
            onChange={(event) => onSyncScrollChange(event.target.checked)}
          />
        }
        label="Sync scroll"
        slotProps={{ typography: { variant: 'body2', color: 'text.secondary' } }}
        sx={{ mr: 0 }}
      />
    </Paper>
  );
}
