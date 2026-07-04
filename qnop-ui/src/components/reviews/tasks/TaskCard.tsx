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

import type { DragEvent } from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { MessageSquare } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { ToneBadge } from '../../admin/ToneBadge';
import { UserAvatar } from '../../shell/UserAvatar';
import { tokens } from '../../../theme/tokens';
import { STATUS_CUES } from '../panel/statusCues';
import { PRIORITY_CUES, TYPE_CUES, taskTitle } from './tasksModel';

/** The DnD payload key the board's drop target reads (issue #393). */
export const TASK_DRAG_TYPE = 'text/qnop-annotation';

interface TaskCardProps {
  annotation: AnnotationView;
  /** Tracker-style shorthand ("T-3") — display only, the UUID stays the identity. */
  taskKey: string;
  authorName: string;
  /** True when the viewer may decide — only then the card can be dragged to Done. */
  draggable: boolean;
  onOpen: (annotationId: string) => void;
}

/**
 * One annotation as a board card (issue #393, prototype `reviewhub.jsx`):
 * priority dot + type badge + anchor cue on top, the opening comment as the
 * title, the quoted passage as a secondary line, author and thread size at
 * the bottom. Shares the annotation cards' 6px radius — one system.
 */
export function TaskCard({ annotation, taskKey, authorName, draggable, onOpen }: TaskCardProps) {
  const theme = useTheme();
  const type = annotation.type ? TYPE_CUES[annotation.type] : null;
  const priority = annotation.priority ? PRIORITY_CUES[annotation.priority] : null;
  const quote = annotation.anchor?.textQuote?.quote;
  const page = annotation.anchor?.region ? annotation.anchor.region.surfaceIndex + 1 : null;
  const statusCue = STATUS_CUES[annotation.status];
  const TypeIcon = type?.icon;

  const onDragStart = (event: DragEvent) => {
    event.dataTransfer.setData(TASK_DRAG_TYPE, annotation.id);
    event.dataTransfer.effectAllowed = 'move';
  };

  return (
    <ButtonBase
      onClick={() => onOpen(annotation.id)}
      draggable={draggable}
      onDragStart={draggable ? onDragStart : undefined}
      data-testid={`task-card-${annotation.id}`}
      sx={{
        display: 'block',
        width: '100%',
        textAlign: 'left',
        p: 1.5,
        pl: 1.25,
        borderRadius: 0.75,
        border: '1px solid',
        borderColor: 'divider',
        // The tracker card language (YouTrack/Zoho): a coloured left edge
        // carrying the priority, a white card lifted off the tinted lane.
        borderLeft: '3px solid',
        borderLeftColor: priority ? priority.color(theme) : theme.palette.divider,
        boxShadow: tokens.shadow.xs,
        bgcolor: 'background.paper',
        cursor: draggable ? 'grab' : 'pointer',
        transition: 'box-shadow 140ms ease, border-color 140ms ease, transform 140ms ease',
        '&:hover': {
          borderColor: theme.palette.text.disabled,
          transform: 'translateY(-1px)',
          boxShadow: `0 8px 22px -10px ${alpha(theme.qnop.brand.navy, 0.25)}`,
        },
        '&:focus-visible': { boxShadow: theme.qnop.focusRing },
        '@media (prefers-reduced-motion: reduce)': { transition: 'none', transform: 'none' },
      }}
    >
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mb: 0.75 }}>
        <Tooltip title={priority ? `${priority.label} priority` : 'No priority'}>
          <Typography
            component="span"
            data-testid="task-key"
            sx={{
              fontFamily: tokens.font.mono,
              fontSize: 11,
              fontWeight: 600,
              color: 'text.secondary',
              letterSpacing: '0.02em',
            }}
          >
            {taskKey}
          </Typography>
        </Tooltip>
        {type && TypeIcon && (
          <Stack
            direction="row"
            spacing={0.5}
            sx={{ alignItems: 'center', color: type.color(theme) }}
          >
            <TypeIcon size={12} aria-hidden />
            <Typography component="span" sx={{ fontSize: 11, fontWeight: 600 }}>
              {type.label}
            </Typography>
          </Stack>
        )}
        <Box sx={{ flex: 1 }} />
        {page !== null && (
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ fontVariantNumeric: 'tabular-nums' }}
          >
            p. {page}
          </Typography>
        )}
      </Stack>

      <Typography
        variant="body2"
        sx={{
          fontWeight: 500,
          lineHeight: 1.35,
          mb: quote ? 0.5 : 1,
          display: '-webkit-box',
          WebkitLineClamp: 3,
          WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
        }}
      >
        {taskTitle(annotation)}
      </Typography>
      {quote && (
        <Typography
          variant="caption"
          noWrap
          sx={{ display: 'block', fontStyle: 'italic', color: 'text.secondary', mb: 1 }}
        >
          “{quote}”
        </Typography>
      )}

      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
        <UserAvatar name={authorName} size={20} />
        <Typography variant="caption" color="text.secondary" noWrap sx={{ minWidth: 0 }}>
          {authorName}
        </Typography>
        <Box sx={{ flex: 1 }} />
        {annotation.status !== AnnotationStatus.Open && (
          <ToneBadge tone={statusCue.tone} label={statusCue.label} />
        )}
        <Stack
          direction="row"
          spacing={0.5}
          sx={{ alignItems: 'center', color: 'text.secondary', flexShrink: 0 }}
        >
          <MessageSquare size={12} aria-hidden />
          <Typography variant="caption" aria-label={`${annotation.commentCount} comments`}>
            {annotation.commentCount}
          </Typography>
        </Stack>
      </Stack>
    </ButtonBase>
  );
}
