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
import LinearProgress from '@mui/material/LinearProgress';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { FileText, Play, Users } from 'lucide-react';
import type { PrincipalView } from '../../../api/generated';
import { ParticipantKind } from '../../../api/generated';
import { DueDatePicker } from '../DueDatePicker';
import { UserAvatar } from '../../shell/UserAvatar';
import { formatFileSize } from './wizardModel';

export type SubmitPhase = 'idle' | 'uploading' | 'finalizing';

interface SummaryStepProps {
  file: File;
  title: string;
  reviewers: PrincipalView[];
  dueAt: string | null;
  onDueAtChange: (value: string | null) => void;
  startImmediately: boolean;
  onStartImmediatelyChange: (value: boolean) => void;
  phase: SubmitPhase;
  /** Upload progress 0..1, meaningful while phase is `uploading`. */
  progress: number;
}

function SummaryRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Box>
      <Typography
        variant="caption"
        sx={{
          display: 'block',
          textTransform: 'uppercase',
          letterSpacing: '0.06em',
          fontSize: 10,
          color: 'text.disabled',
          mb: 0.5,
        }}
      >
        {label}
      </Typography>
      {children}
    </Box>
  );
}

/** Step 3 — confirm what will be created and optionally start the review. */
export function SummaryStep({
  file,
  title,
  reviewers,
  dueAt,
  onDueAtChange,
  startImmediately,
  onStartImmediatelyChange,
  phase,
  progress,
}: SummaryStepProps) {
  const theme = useTheme();
  const isSubmitting = phase !== 'idle';

  return (
    <Stack spacing={3}>
      <SummaryRow label="Document">
        <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center' }}>
          <FileText size={16} aria-hidden style={{ color: theme.palette.text.secondary }} />
          <Typography variant="body2" sx={{ fontWeight: 500 }}>
            {title}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {file.name} · {formatFileSize(file.size)}
          </Typography>
        </Stack>
      </SummaryRow>

      <SummaryRow label="Reviewers">
        {reviewers.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            None yet — the review starts with you only; reviewers can be added any time.
          </Typography>
        ) : (
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
            {reviewers.map((reviewer) => (
              <Stack
                key={`${reviewer.kind}:${reviewer.id}`}
                direction="row"
                spacing={0.75}
                data-testid={`summary-reviewer-${reviewer.id}`}
                sx={{
                  alignItems: 'center',
                  pl: 0.5,
                  pr: 1.25,
                  py: 0.5,
                  borderRadius: 99,
                  bgcolor: theme.qnop.surface2,
                }}
              >
                {reviewer.kind === ParticipantKind.Team ? (
                  <Box
                    sx={{
                      width: 22,
                      height: 22,
                      borderRadius: '50%',
                      bgcolor: theme.palette.background.paper,
                      color: 'text.secondary',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0,
                    }}
                  >
                    <Users size={12} aria-hidden />
                  </Box>
                ) : (
                  <UserAvatar name={reviewer.displayName} size={22} imageUrl={reviewer.avatarUrl} />
                )}
                <Typography variant="body2" sx={{ fontSize: 12.5, fontWeight: 500 }}>
                  {reviewer.displayName}
                </Typography>
              </Stack>
            ))}
          </Stack>
        )}
      </SummaryRow>

      <SummaryRow label="Due date">
        <Box sx={{ maxWidth: 280 }}>
          <DueDatePicker value={dueAt} onChange={onDueAtChange} disabled={isSubmitting} />
        </Box>
      </SummaryRow>

      <Stack
        component="label"
        direction="row"
        spacing={1.5}
        sx={{
          alignItems: 'center',
          p: 1.75,
          borderRadius: 2,
          cursor: isSubmitting ? 'default' : 'pointer',
          border: `1px solid ${startImmediately ? theme.qnop.brand.blue : theme.palette.divider}`,
          bgcolor: startImmediately ? theme.palette.primary.light : 'transparent',
          transition: 'border-color 160ms ease, background-color 160ms ease',
        }}
      >
        <Box
          sx={{
            width: 32,
            height: 32,
            borderRadius: 2,
            flexShrink: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            bgcolor: startImmediately ? theme.qnop.brand.blue : theme.qnop.surface2,
            color: startImmediately ? '#fff' : 'text.secondary',
          }}
        >
          <Play size={16} aria-hidden />
        </Box>
        <Box sx={{ flex: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 500 }}>
            Start review immediately
          </Typography>
          <Typography variant="caption" color="text.secondary">
            Moves the review from Draft to In review so reviewers can annotate right away.
          </Typography>
        </Box>
        <Switch
          checked={startImmediately}
          disabled={isSubmitting}
          onChange={(e) => onStartImmediatelyChange(e.target.checked)}
          slotProps={{ input: { 'aria-label': 'Start review immediately' } }}
        />
      </Stack>

      {isSubmitting && (
        <Box data-testid="wizard-upload-progress">
          <Stack direction="row" sx={{ justifyContent: 'space-between', mb: 0.75 }}>
            <Typography variant="caption" color="text.secondary">
              {phase === 'uploading' ? 'Uploading document…' : 'Setting up the review…'}
            </Typography>
            {phase === 'uploading' && (
              <Typography variant="caption" color="text.secondary">
                {Math.round(progress * 100)}%
              </Typography>
            )}
          </Stack>
          <LinearProgress
            variant={phase === 'uploading' ? 'determinate' : 'indeterminate'}
            value={progress * 100}
            sx={{ borderRadius: 99 }}
          />
        </Box>
      )}
    </Stack>
  );
}
