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

import Tooltip from '@mui/material/Tooltip';
import { useTheme } from '@mui/material/styles';
import type { LucideIcon } from 'lucide-react';
import { Ban, Circle, CircleCheckBig, CircleDashed, CircleDot } from 'lucide-react';
import { workflowLabel } from './workflowMeta';

/**
 * The review workflow state as ONE glyph (issue #540) — the milestone path of
 * {@link WorkflowMilestones} (#568) compressed to its current position, for
 * dense rows where even the dot track is too wide. Same visual vocabulary:
 * the live pair as a filled dot (blue in review, amber on changes requested),
 * FINALIZED as the green arrival check, CANCELLED as the red side exit,
 * DRAFT as a dashed not-yet. Unknown (enterprise) states render a neutral
 * circle. The state name travels in the tooltip and the accessible label.
 */
export function WorkflowStateIcon({ state, size = 16 }: { state: string; size?: number }) {
  const theme = useTheme();

  let Icon: LucideIcon;
  let color: string;
  switch (state) {
    case 'DRAFT':
      Icon = CircleDashed;
      color = theme.palette.text.disabled;
      break;
    case 'IN_REVIEW':
      Icon = CircleDot;
      color = theme.qnop.brand.blue;
      break;
    case 'CHANGES_REQUESTED':
      Icon = CircleDot;
      color = theme.qnop.badge.amber.fg;
      break;
    case 'FINALIZED':
      Icon = CircleCheckBig;
      color = theme.palette.success.main;
      break;
    case 'CANCELLED':
      Icon = Ban;
      color = theme.qnop.badge.red.fg;
      break;
    default:
      Icon = Circle;
      color = theme.palette.text.disabled;
  }

  const label = workflowLabel(state);
  return (
    <Tooltip title={label} describeChild>
      <Icon
        size={size}
        role="img"
        aria-label={label}
        data-testid="workflow-state-icon"
        style={{ color, flexShrink: 0 }}
      />
    </Tooltip>
  );
}
