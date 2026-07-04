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
import ButtonBase from '@mui/material/ButtonBase';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { ChevronRight, MessageSquare } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { ToneBadge } from '../../admin/ToneBadge';
import { UserAvatar } from '../../shell/UserAvatar';
import { tokens } from '../../../theme/tokens';
import { STATUS_CUES } from '../panel/statusCues';
import { PRIORITY_CUES, TYPE_CUES, columnOf, taskTitle } from './tasksModel';

interface TaskListRowsProps {
  annotations: AnnotationView[];
  /** Tracker-style shorthand per annotation id ("T-3"). */
  taskKeyOf: (annotationId: string) => string;
  authorNameOf: (authorId: string) => string;
  onOpen: (annotationId: string) => void;
}

/**
 * The list presentation (issue #393): the board's cards as dense single rows —
 * priority dot, type, title with quote/page meta, status badge, author,
 * chevron — for reviews where scanning beats spatial grouping.
 */
export function TaskListRows({ annotations, taskKeyOf, authorNameOf, onOpen }: TaskListRowsProps) {
  const theme = useTheme();
  return (
    <Paper
      variant="outlined"
      sx={{ overflow: 'hidden', maxWidth: 1100, mx: 'auto', width: '100%' }}
    >
      {annotations.map((annotation, index) => {
        const type = annotation.type ? TYPE_CUES[annotation.type] : null;
        const priority = annotation.priority ? PRIORITY_CUES[annotation.priority] : null;
        const TypeIcon = type?.icon;
        const statusCue = STATUS_CUES[annotation.status];
        const column = columnOf(annotation);
        const page = annotation.anchor?.region ? annotation.anchor.region.surfaceIndex + 1 : null;
        return (
          <ButtonBase
            key={annotation.id}
            onClick={() => onOpen(annotation.id)}
            data-testid={`task-row-${annotation.id}`}
            sx={{
              display: 'flex',
              width: '100%',
              textAlign: 'left',
              alignItems: 'center',
              gap: 1.5,
              px: 2,
              py: 1.5,
              borderTop: index ? '1px solid' : 'none',
              borderColor: 'divider',
              '&:hover': { bgcolor: 'action.hover' },
              '&:focus-visible': { boxShadow: theme.qnop.focusRing },
            }}
          >
            <Tooltip title={priority ? `${priority.label} priority` : 'No priority'}>
              <Box
                sx={{
                  width: 8,
                  height: 8,
                  borderRadius: '50%',
                  bgcolor: priority ? priority.color(theme) : 'transparent',
                  border: priority ? 'none' : `1px solid ${theme.palette.divider}`,
                  flexShrink: 0,
                }}
              />
            </Tooltip>
            <Typography
              component="span"
              sx={{
                fontFamily: tokens.font.mono,
                fontSize: 11,
                fontWeight: 600,
                color: 'text.secondary',
                width: 34,
                flexShrink: 0,
              }}
            >
              {taskKeyOf(annotation.id)}
            </Typography>
            <Stack
              direction="row"
              spacing={0.5}
              sx={{
                alignItems: 'center',
                width: 92,
                flexShrink: 0,
                color: type ? type.color(theme) : 'text.disabled',
              }}
            >
              {TypeIcon && <TypeIcon size={13} aria-hidden />}
              <Typography component="span" sx={{ fontSize: 11.5, fontWeight: 600 }} noWrap>
                {type ? type.label : '—'}
              </Typography>
            </Stack>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography variant="body2" noWrap sx={{ fontWeight: 500 }}>
                {taskTitle(annotation)}
              </Typography>
              <Typography variant="caption" color="text.secondary" noWrap sx={{ display: 'block' }}>
                {annotation.anchor?.textQuote?.quote
                  ? `“${annotation.anchor.textQuote.quote}”`
                  : 'No text anchor'}
                {page !== null && ` · p. ${page}`}
                {` · ${authorNameOf(annotation.authorId)}`}
              </Typography>
            </Box>
            <Stack
              direction="row"
              spacing={0.5}
              sx={{ alignItems: 'center', color: 'text.secondary', flexShrink: 0 }}
            >
              <MessageSquare size={12} aria-hidden />
              <Typography variant="caption">{annotation.commentCount}</Typography>
            </Stack>
            <ToneBadge
              tone={annotation.status === AnnotationStatus.Open ? 'blue' : statusCue.tone}
              label={
                annotation.status === AnnotationStatus.Open
                  ? column === 'discussion'
                    ? 'In discussion'
                    : 'Open'
                  : statusCue.label
              }
            />
            <UserAvatar name={authorNameOf(annotation.authorId)} size={22} />
            <ChevronRight size={15} aria-hidden color={theme.palette.text.disabled} />
          </ButtonBase>
        );
      })}
      {annotations.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ p: 3, textAlign: 'center' }}>
          No annotations match the current filter.
        </Typography>
      )}
    </Paper>
  );
}
