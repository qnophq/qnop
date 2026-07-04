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

import { useState } from 'react';
import type { DragEvent } from 'react';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import type { Theme } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { ToneBadge } from '../../admin/ToneBadge';
import { TaskCard, TASK_DRAG_TYPE } from './TaskCard';
import type { TaskColumn } from './tasksModel';
import { TASK_COLUMNS, columnOf } from './tasksModel';

const COLUMN_CUES: Record<
  TaskColumn,
  { label: string; tone: 'blue' | 'amber' | 'green'; color: (theme: Theme) => string }
> = {
  open: { label: 'Open', tone: 'blue', color: (theme) => theme.qnop.brand.blue },
  discussion: {
    label: 'In discussion',
    tone: 'amber',
    color: (theme) => theme.palette.warning.main,
  },
  done: { label: 'Done', tone: 'green', color: (theme) => theme.palette.success.main },
};

interface TaskBoardProps {
  annotations: AnnotationView[];
  /** The previous visit (issue #307). */
  previousSeenAt?: string | null;
  /** Tracker-style shorthand per annotation id ("T-3"). */
  taskKeyOf: (annotationId: string) => string;
  authorNameOf: (authorId: string) => string;
  /** Whether the viewer may decide this annotation — gates dragging to Done. */
  mayDecide: (annotation: AnnotationView) => boolean;
  onOpen: (annotationId: string) => void;
  /** Dropping an undecided card on Done accepts it (the drawer offers reject). */
  onAccept: (annotationId: string) => void;
}

/**
 * The kanban presentation (issue #393, prototype `reviewhub.jsx`): three
 * columns — Open, the derived In discussion, Done — with independently
 * scrolling card stacks. Done is the only drop target (Open ↔ In discussion
 * is derived state); the drag-over column shows the prototype's dashed
 * accent outline. Deciding via the drawer stays the keyboard path.
 */
export function TaskBoard({
  annotations,
  previousSeenAt = null,
  taskKeyOf,
  authorNameOf,
  mayDecide,
  onOpen,
  onAccept,
}: TaskBoardProps) {
  const theme = useTheme();
  const [dropActive, setDropActive] = useState(false);

  const itemsOf = (column: TaskColumn) =>
    annotations.filter((annotation) => columnOf(annotation) === column);

  const onDragOver = (event: DragEvent) => {
    if (!event.dataTransfer.types.includes(TASK_DRAG_TYPE)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
    setDropActive(true);
  };

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDropActive(false);
    const annotationId = event.dataTransfer.getData(TASK_DRAG_TYPE);
    if (annotationId) onAccept(annotationId);
  };

  return (
    <Box
      data-testid="task-board"
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(232px, 1fr))' },
        gap: 1.75,
        flex: 1,
        minHeight: 0,
        alignItems: 'stretch',
      }}
    >
      {TASK_COLUMNS.map((column) => {
        const cue = COLUMN_CUES[column];
        const items = itemsOf(column);
        const isDone = column === 'done';
        return (
          <Stack
            key={column}
            data-testid={`task-column-${column}`}
            onDragOver={isDone ? onDragOver : undefined}
            onDragLeave={isDone ? () => setDropActive(false) : undefined}
            onDrop={isDone ? onDrop : undefined}
            sx={{
              borderRadius: 0.75,
              border: '1px solid',
              borderColor: 'divider',
              // The board contrast the trackers share: tinted lanes, white
              // cards lifted on top; lanes run the full board height.
              bgcolor: theme.qnop.surface2,
              minHeight: { xs: 0, md: 480 },
              overflow: 'hidden',
              ...(isDone &&
                dropActive && {
                  outline: `2px dashed ${theme.qnop.brand.blue}`,
                  outlineOffset: -2,
                  bgcolor: alpha(theme.qnop.brand.blue, 0.05),
                }),
            }}
          >
            <Stack
              direction="row"
              spacing={1}
              sx={{
                alignItems: 'center',
                px: 1.25,
                py: 1.1,
                borderBottom: '1px solid',
                borderColor: 'divider',
                bgcolor: 'background.paper',
              }}
            >
              <ToneBadge tone={cue.tone} label={`${cue.label} (${items.length})`} />
              <Box sx={{ flex: 1 }} />
              <Box
                sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: cue.color(theme) }}
                aria-hidden
              />
            </Stack>
            <Stack spacing={1.25} sx={{ flex: 1, overflowY: 'auto', p: 1.25, minHeight: 96 }}>
              {items.map((annotation) => (
                <TaskCard
                  key={annotation.id}
                  annotation={annotation}
                  previousSeenAt={previousSeenAt}
                  taskKey={taskKeyOf(annotation.id)}
                  authorName={authorNameOf(annotation.authorId)}
                  draggable={!isDone && mayDecide(annotation)}
                  onOpen={onOpen}
                />
              ))}
              {items.length === 0 && (
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ textAlign: 'center', py: 3 }}
                >
                  {isDone ? 'Drop a card here to accept it' : 'Nothing here'}
                </Typography>
              )}
            </Stack>
          </Stack>
        );
      })}
    </Box>
  );
}
