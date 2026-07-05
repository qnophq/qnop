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
import Drawer from '@mui/material/Drawer';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { ExternalLink, X } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { useAuthStore } from '../../../stores/authStore';
import { tokens } from '../../../theme/tokens';
import type { Notify } from '../../admin/layout/useToast';
import { ToneBadge } from '../../admin/ToneBadge';
import { CommentThread } from '../panel/CommentThread';
import { ResolveBar } from '../panel/ResolveBar';
import { mayResolveAnnotation, useResolveWithFeedback } from '../panel/resolve';
import { PlacementStatusChip } from '../panel/PlacementStatusChip';
import { STATUS_CUES } from '../panel/statusCues';
import { PRIORITY_CUES, TYPE_CUES, taskTitle } from './tasksModel';

interface TaskDrawerProps {
  annotation: AnnotationView | null;
  /** The previous visit (issue #307) — enables the thread's "new" divider. */
  previousSeenAt?: string | null;
  /** Tracker-style shorthand ("T-3") of the open annotation. */
  taskKey: string;
  authorName: string;
  notify: Notify;
  onClose: () => void;
  /** Jumps to the review page with this annotation active (deep link). */
  onShowInDocument: (annotationId: string) => void;
}

/**
 * The task's issue-detail drawer (issue #393, prototype `reviewhub.jsx`):
 * type/priority/anchor header, the opening comment as the title, the full
 * discussion thread and the author's Resolve bar — reusing the panel's
 * `CommentThread` and `ResolveBar`, so a thread reads and behaves identically
 * on every surface. Also the keyboard path for resolving (the board's
 * drag-to-Resolved is a pointer shortcut).
 */
export function TaskDrawer({
  annotation,
  previousSeenAt = null,
  taskKey,
  authorName,
  notify,
  onClose,
  onShowInDocument,
}: TaskDrawerProps) {
  const theme = useTheme();
  const userId = useAuthStore((state) => state.userId);
  const { resolveWith, isPending } = useResolveWithFeedback(notify);

  if (!annotation) return null;
  const type = annotation.type ? TYPE_CUES[annotation.type] : null;
  const priority = annotation.priority ? PRIORITY_CUES[annotation.priority] : null;
  const TypeIcon = type?.icon;
  const statusCue = STATUS_CUES[annotation.status];
  const page = annotation.anchor?.region ? annotation.anchor.region.surfaceIndex + 1 : null;

  return (
    <Drawer
      anchor="right"
      open
      onClose={onClose}
      slotProps={{ paper: { sx: { width: { xs: '100%', sm: 460 } } } }}
      data-testid="task-drawer"
    >
      <Stack sx={{ height: '100%' }}>
        <Box sx={{ p: 2, borderBottom: '1px solid', borderColor: 'divider' }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
            <Typography
              component="span"
              sx={{
                fontFamily: tokens.font.mono,
                fontSize: 12,
                fontWeight: 600,
                color: 'text.secondary',
              }}
            >
              {taskKey}
            </Typography>
            {type && TypeIcon && (
              <Stack
                direction="row"
                spacing={0.5}
                sx={{ alignItems: 'center', color: type.color(theme) }}
              >
                <TypeIcon size={13} aria-hidden />
                <Typography component="span" sx={{ fontSize: 11.5, fontWeight: 600 }}>
                  {type.label}
                </Typography>
              </Stack>
            )}
            {priority && (
              <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                <Box
                  sx={{
                    width: 6,
                    height: 6,
                    borderRadius: '50%',
                    bgcolor: priority.color(theme),
                  }}
                  aria-hidden
                />
                <Typography variant="caption" color="text.secondary">
                  {priority.label}
                </Typography>
              </Stack>
            )}
            <Box sx={{ flex: 1 }} />
            <IconButton size="small" onClick={onClose} aria-label="Close task">
              <X size={16} />
            </IconButton>
          </Stack>
          <Typography variant="subtitle1" sx={{ fontWeight: 600, lineHeight: 1.3 }}>
            {taskTitle(annotation)}
          </Typography>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 1, flexWrap: 'wrap' }}>
            <ToneBadge tone={statusCue.tone} label={statusCue.label} />
            <PlacementStatusChip status={annotation.placementStatus} />
            {page !== null && (
              <Typography variant="caption" color="text.secondary">
                p. {page}
              </Typography>
            )}
            <Typography variant="caption" color="text.secondary">
              · opened by {authorName}
            </Typography>
          </Stack>
          {annotation.anchor?.textQuote?.quote && (
            <Typography
              variant="body2"
              sx={{
                mt: 1.25,
                fontStyle: 'italic',
                color: 'text.secondary',
                borderLeft: '3px solid',
                borderColor: 'divider',
                pl: 1.25,
                display: '-webkit-box',
                WebkitLineClamp: 3,
                WebkitBoxOrient: 'vertical',
                overflow: 'hidden',
              }}
            >
              “{annotation.anchor.textQuote.quote}”
            </Typography>
          )}
          <Button
            size="small"
            variant="text"
            startIcon={<ExternalLink size={13} />}
            onClick={() => onShowInDocument(annotation.id)}
            sx={{ mt: 1 }}
          >
            Show in document
          </Button>
        </Box>

        <Box sx={{ flex: 1, overflowY: 'auto', px: 2, py: 1 }}>
          <CommentThread
            annotationId={annotation.id}
            notify={notify}
            previousSeenAt={previousSeenAt}
            skipOpener
          />
        </Box>

        {mayResolveAnnotation(annotation, userId) && (
          <Box sx={{ borderTop: '1px solid', borderColor: 'divider', px: 1, py: 0.5 }}>
            <ResolveBar disabled={isPending} onResolve={(note) => resolveWith(annotation, note)} />
          </Box>
        )}
      </Stack>
    </Drawer>
  );
}
