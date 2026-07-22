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
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { Ban, CircleCheckBig } from 'lucide-react';
import { WorkflowBadge } from './WorkflowBadge';
import { milestoneIndex, workflowLabel } from './workflowMeta';

interface WorkflowMilestonesProps {
  /** The current workflow state (open set — unknown states fall back to the flat badge). */
  state: string;
  /** Annotation counts feeding the live stage's progress; omit when unknown. */
  total?: number;
  resolved?: number;
}

const DOT = 9;

/**
 * The review's position on its milestone path (issue #568), replacing the flat
 * status pill: Draft → In review → Finalized as a compact track, the derived
 * `IN_REVIEW ⇄ CHANGES_REQUESTED` pair (#405) rendered as ONE live stage whose
 * tone and label ping-pong with the open-annotation count. CANCELLED is the
 * side exit (a terminated track), FINALIZED the celebratory arrival. Purely
 * informational — reviewers and owners read the same path. The pulse on the
 * live stage is compositor-only and stops under prefers-reduced-motion;
 * unknown (enterprise) states render the plain badge instead of guessing.
 */
export function WorkflowMilestones({ state, total = 0, resolved = 0 }: WorkflowMilestonesProps) {
  const theme = useTheme();
  const position = milestoneIndex(state);
  if (position === null) {
    return <WorkflowBadge state={state} />;
  }

  const open = Math.max(0, total - resolved);
  const label = workflowLabel(state);
  const cancelled = position === 'cancelled';
  const finalized = position === 2;
  const activeIndex = cancelled ? -1 : position;
  const activeTone =
    state === 'CHANGES_REQUESTED'
      ? theme.qnop.badge.amber
      : { bg: theme.qnop.surface2, fg: theme.qnop.brand.blue, border: theme.palette.divider };

  const summary = cancelled
    ? `Review cancelled${open > 0 ? ` — ${open} annotation${open === 1 ? '' : 's'} were closed automatically` : ''}`
    : finalized
      ? 'Review finalized — every concern settled'
      : position === 0
        ? 'Draft — not yet in review'
        : total > 0
          ? `${label} — ${resolved} of ${total} annotation${total === 1 ? '' : 's'} resolved${open > 0 ? `, ${open} to go` : ', ready to finalize'}`
          : `${label} — no annotations yet`;

  const dot = (index: number) => {
    const done = finalized || index < activeIndex;
    const active = !finalized && index === activeIndex;
    return (
      <Box
        key={index}
        aria-hidden
        sx={{
          width: DOT,
          height: DOT,
          borderRadius: '50%',
          flexShrink: 0,
          boxSizing: 'border-box',
          ...(done && { bgcolor: theme.palette.success.main }),
          ...(active && {
            bgcolor: activeTone.fg,
            // The one deliberate motion moment: the live stage breathes.
            // Compositor-only (transform/opacity) and off under reduced motion.
            animation: 'qnop-milestone-pulse 2.4s ease-in-out infinite',
            '@keyframes qnop-milestone-pulse': {
              '0%, 100%': { transform: 'scale(1)', opacity: 1 },
              '50%': { transform: 'scale(1.35)', opacity: 0.65 },
            },
            '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
          }),
          ...(!done &&
            !active && {
              bgcolor: 'transparent',
              border: `1.5px solid ${cancelled ? theme.palette.text.disabled : theme.palette.divider}`,
            }),
        }}
      />
    );
  };

  const connector = (index: number) => {
    const filled = finalized || index < activeIndex;
    return (
      <Box
        key={`c${index}`}
        aria-hidden
        sx={{
          width: 14,
          height: 2,
          borderRadius: 1,
          flexShrink: 0,
          bgcolor: filled ? theme.palette.success.main : theme.palette.divider,
          ...(cancelled && { bgcolor: theme.palette.text.disabled, opacity: 0.4 }),
        }}
      />
    );
  };

  return (
    <Tooltip title={summary} describeChild>
      <Box
        role="img"
        aria-label={summary}
        data-testid="workflow-milestones"
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.75,
          height: 26,
          px: 1.25,
          borderRadius: 99,
          border: '1px solid',
          borderColor: cancelled ? theme.qnop.badge.red.border : theme.palette.divider,
          bgcolor: cancelled ? theme.qnop.badge.red.bg : theme.qnop.surface2,
          whiteSpace: 'nowrap',
        }}
      >
        {cancelled ? (
          <Ban size={13} aria-hidden style={{ color: theme.qnop.badge.red.fg, flexShrink: 0 }} />
        ) : (
          dot(0)
        )}
        {connector(0)}
        {!cancelled && dot(1)}
        {cancelled && (
          <Typography
            component="span"
            sx={{ fontSize: 12, fontWeight: 600, color: theme.qnop.badge.red.fg }}
          >
            {label}
          </Typography>
        )}
        {!cancelled && !finalized && (
          <Typography
            component="span"
            sx={{
              fontSize: 12,
              fontWeight: 600,
              color: position === 1 ? activeTone.fg : theme.palette.text.secondary,
            }}
          >
            {position === 0 ? 'Draft' : label}
            {position === 1 && total > 0 && (
              <Box component="span" sx={{ fontWeight: 400, color: 'text.secondary', ml: 0.5 }}>
                {resolved}/{total}
              </Box>
            )}
          </Typography>
        )}
        {connector(1)}
        {finalized ? (
          <>
            <CircleCheckBig
              size={14}
              aria-hidden
              style={{ color: theme.palette.success.main, flexShrink: 0 }}
            />
            <Typography
              component="span"
              sx={{ fontSize: 12, fontWeight: 600, color: theme.palette.success.main }}
            >
              Finalized
            </Typography>
          </>
        ) : (
          dot(2)
        )}
      </Box>
    </Tooltip>
  );
}
