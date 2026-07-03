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

import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { AlarmClock, CalendarClock } from 'lucide-react';
import { formatDueDate, isPast } from '../../utils/formatDate';
import { isOpenWorkflowState } from './workflowMeta';

interface DueDateLabelProps {
  dueAt: string | null | undefined;
  /** The workflow state; an overdue review is only flagged while still open. */
  workflowState: string;
  /** Typography variant for the text (default `caption`). */
  variant?: 'caption' | 'body2';
}

/**
 * A review's due date as a clock + relative phrase ("due in 3 days", "overdue by
 * 2 days", issue #295). An overdue *open* review is flagged in the danger tone
 * with an alarm icon; once the review is closed the deadline reads as neutral
 * history — a passed deadline on a finished review is not a problem.
 */
export function DueDateLabel({ dueAt, workflowState, variant = 'caption' }: DueDateLabelProps) {
  if (!dueAt) return null;
  const overdue = isPast(dueAt) && isOpenWorkflowState(workflowState);
  const Icon = overdue ? AlarmClock : CalendarClock;
  return (
    <Stack
      direction="row"
      spacing={0.5}
      sx={{
        alignItems: 'center',
        color: overdue ? 'error.main' : 'text.secondary',
        minWidth: 0,
      }}
    >
      <Icon size={variant === 'body2' ? 15 : 13} aria-hidden />
      <Typography
        variant={variant}
        noWrap
        sx={{ fontWeight: overdue ? 600 : 400 }}
        data-overdue={overdue || undefined}
      >
        {formatDueDate(dueAt)}
      </Typography>
    </Stack>
  );
}
