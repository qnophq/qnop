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

import { useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Popover from '@mui/material/Popover';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { MessageSquare } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import type { ScreenPosition } from './anchoring';
import { AnnotationStatus } from '../../../api/generated';
import { useComments } from '../../../api/hooks/useComments';
import { useAuthStore } from '../../../stores/authStore';
import type { BadgeTone } from '../../admin/ToneBadge';
import { ToneBadge } from '../../admin/ToneBadge';
import { UserAvatar } from '../../shell/UserAvatar';
import { PlacementStatusChip } from '../panel/PlacementStatusChip';
import { highlightColorFor } from './markerColors';

/** Hover intent: the preview appears only after the pointer settles on a mark. */
const SHOW_DELAY_MS = 320;

const STATUS_CUES: Record<AnnotationStatus, { tone: BadgeTone; label: string }> = {
  [AnnotationStatus.Open]: { tone: 'blue', label: 'Open' },
  [AnnotationStatus.Accepted]: { tone: 'green', label: 'Accepted' },
  [AnnotationStatus.Rejected]: { tone: 'neutral', label: 'Rejected' },
};

const TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

/** Vertical offset so the card sits just below the pointer, not under it. */
const POINTER_OFFSET_PX = 14;

interface AnnotationHoverCardProps {
  annotation: AnnotationView;
  /** The current pointer position — read once when the card becomes visible. */
  getAnchorPosition: () => ScreenPosition | null;
}

/**
 * The mark's hover preview: who opened the discussion and where it stands,
 * without leaving the document. Shows the first comment with its author's
 * avatar, the annotation status, the placement cue and the thread size.
 * Hovering also warms the comments cache, so opening the thread afterwards is
 * instant. Pointer events pass through — the card never traps the mouse.
 */
export function AnnotationHoverCard({ annotation, getAnchorPosition }: AnnotationHoverCardProps) {
  const theme = useTheme();
  const userId = useAuthStore((state) => state.userId);
  const displayName = useAuthStore((state) => state.displayName);
  const avatarUrl = useAuthStore((state) => state.avatarUrl);
  const commentsQuery = useComments(annotation.id, annotation.commentCount > 0);

  // Hide instantly when the hovered mark changes (adjust-state-during-render
  // pattern), then re-show after the hover-intent delay — frozen at wherever
  // the pointer settled, so the card does not chase the mouse.
  const [position, setPosition] = useState<ScreenPosition | null>(null);
  const [lastId, setLastId] = useState(annotation.id);
  if (annotation.id !== lastId) {
    setLastId(annotation.id);
    setPosition(null);
  }
  useEffect(() => {
    const timer = setTimeout(() => setPosition(getAnchorPosition()), SHOW_DELAY_MS);
    return () => clearTimeout(timer);
    // getAnchorPosition is a stable ref reader from the viewer.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [annotation.id]);

  const firstComment = commentsQuery.data?.comments[0];
  const authorId = firstComment?.authorId ?? annotation.authorId;
  const own = authorId === userId;
  const authorName = own ? (displayName ?? 'You') : 'Participant';
  const statusCue = STATUS_CUES[annotation.status];
  const railColor = highlightColorFor(annotation, theme.palette);
  const commentLabel =
    annotation.commentCount === 1 ? '1 comment' : `${annotation.commentCount} comments`;

  return (
    <Popover
      open={Boolean(position)}
      anchorReference="anchorPosition"
      anchorPosition={
        position ? { left: position.left, top: position.top + POINTER_OFFSET_PX } : undefined
      }
      transformOrigin={{ vertical: 'top', horizontal: 'left' }}
      disableAutoFocus
      disableEnforceFocus
      disableRestoreFocus
      // Pure preview: it must never catch the pointer or steal focus.
      sx={{ pointerEvents: 'none' }}
      slotProps={{
        paper: {
          sx: {
            mt: 0.75,
            width: 320,
            borderRadius: 2.5,
            border: `1px solid ${theme.palette.divider}`,
            boxShadow: '0 12px 32px -8px rgba(1, 32, 66, 0.25)',
            overflow: 'hidden',
          },
        },
      }}
    >
      <Box sx={{ position: 'relative', pl: 2, pr: 1.75, py: 1.5 }}>
        {/* The rail binds the card to its mark: same colour as the highlight. */}
        <Box
          aria-hidden
          sx={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 4, bgcolor: railColor }}
        />
        <Stack spacing={1}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <UserAvatar name={authorName} size={28} imageUrl={own ? avatarUrl : null} />
            <Box sx={{ minWidth: 0, flex: 1 }}>
              <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.2 }} noWrap>
                {own ? 'You' : authorName}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {TIME_FORMAT.format(new Date(firstComment?.createdAt ?? annotation.createdAt))}
              </Typography>
            </Box>
            <ToneBadge tone={statusCue.tone} label={statusCue.label} />
          </Stack>

          {annotation.commentCount > 0 ? (
            commentsQuery.isPending ? (
              <Stack spacing={0.5}>
                <Skeleton variant="text" width="90%" />
                <Skeleton variant="text" width="65%" />
              </Stack>
            ) : (
              <Typography
                variant="body2"
                sx={{
                  display: '-webkit-box',
                  WebkitLineClamp: 3,
                  WebkitBoxOrient: 'vertical',
                  overflow: 'hidden',
                  overflowWrap: 'anywhere',
                }}
              >
                {firstComment?.body ?? ''}
              </Typography>
            )
          ) : (
            <Typography variant="body2" color="text.secondary" sx={{ fontStyle: 'italic' }}>
              No comments yet.
            </Typography>
          )}

          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Stack
              direction="row"
              spacing={0.5}
              sx={{ alignItems: 'center', color: 'text.secondary' }}
            >
              <MessageSquare size={13} aria-hidden />
              <Typography variant="caption">{commentLabel}</Typography>
            </Stack>
            <PlacementStatusChip status={annotation.placementStatus} />
            <Typography variant="caption" color="text.secondary" sx={{ ml: 'auto' }}>
              Click to open the thread
            </Typography>
          </Stack>
        </Stack>
      </Box>
    </Popover>
  );
}
