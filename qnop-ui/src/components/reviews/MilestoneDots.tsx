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
import Tooltip from '@mui/material/Tooltip';
import { useTheme } from '@mui/material/styles';
import { Ban } from 'lucide-react';
import { milestoneIndex, workflowLabel } from './workflowMeta';

const DOT = 6;

/**
 * The milestone path of {@link WorkflowMilestones} (#568) at list-row scale
 * (issue #540): three 6px dots, no pill, no pulse, no text — just where the
 * review stands, readable at a glance in a dense hit list. CANCELLED renders
 * the side-exit glyph; unknown (enterprise) states a single neutral dot. The
 * state name travels in the accessible label and the tooltip.
 */
export function MilestoneDots({ state }: { state: string }) {
  const theme = useTheme();
  const position = milestoneIndex(state);
  const label = workflowLabel(state);

  const dot = (filled: boolean, active: boolean, key: number) => (
    <Box
      key={key}
      sx={{
        width: DOT,
        height: DOT,
        borderRadius: '50%',
        flexShrink: 0,
        boxSizing: 'border-box',
        ...(filled && { bgcolor: theme.palette.success.main }),
        ...(active && {
          bgcolor:
            state === 'CHANGES_REQUESTED' ? theme.qnop.badge.amber.fg : theme.qnop.brand.blue,
        }),
        ...(!filled && !active && { border: `1px solid ${theme.palette.divider}` }),
      }}
    />
  );

  const connector = (filled: boolean, key: string) => (
    <Box
      key={key}
      sx={{
        width: 8,
        height: 2,
        borderRadius: 1,
        flexShrink: 0,
        bgcolor: filled ? theme.palette.success.main : theme.palette.divider,
      }}
    />
  );

  let track;
  if (position === 'cancelled') {
    track = <Ban size={12} aria-hidden style={{ color: theme.qnop.badge.red.fg }} />;
  } else if (position === null) {
    track = dot(false, false, 0);
  } else {
    const finalized = position === 2;
    track = [0, 1, 2].flatMap((index) => {
      const done = finalized || index < position;
      const active = !finalized && index === position;
      const pieces = [dot(done, active, index)];
      if (index < 2) pieces.push(connector(finalized || index < position, `c${index}`));
      return pieces;
    });
  }

  return (
    <Tooltip title={label} describeChild>
      <Box
        role="img"
        aria-label={label}
        data-testid="milestone-dots"
        sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, flexShrink: 0 }}
      >
        {track}
      </Box>
    </Tooltip>
  );
}
