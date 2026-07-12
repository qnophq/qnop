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
import type { AnnotationView } from '../../../api/generated';
import type { ScreenPosition } from './anchoring';
import { useComments } from '../../../api/hooks/useComments';
import { useAuthStore } from '../../../stores/authStore';
import { AnnotationBadgeRow } from '../panel/AnnotationBadgeRow';
import { CommentMessage } from '../panel/CommentMessage';
import { avatarSrc } from '../../../utils/avatarUrl';

/** Hover intent: the preview appears only after the pointer settles on a mark. */
const SHOW_DELAY_MS = 320;

/** Vertical offset so the card sits just below the pointer, not under it. */
const POINTER_OFFSET_PX = 14;

interface AnnotationHoverCardProps {
  annotation: AnnotationView;
  /** The current pointer position — read once when the card becomes visible. */
  getAnchorPosition: () => ScreenPosition | null;
}

/**
 * The mark's hover preview: who opened the discussion and where it stands,
 * without leaving the document — speaking exactly the document view's
 * language (issue #403): the panel's badge row on top, the opening comment
 * as the same social bubble the thread renders. Hovering also warms the
 * comments cache, so opening the thread afterwards is instant. Pointer
 * events pass through — the card never traps the mouse.
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
  // Server-resolved name, honouring per-review anonymity (issue #413).
  const authorName = own
    ? (displayName ?? 'You')
    : (firstComment?.authorDisplayName ?? annotation.authorDisplayName ?? 'Participant');

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
            borderRadius: 0.75,
            border: `1px solid ${theme.palette.divider}`,
            boxShadow:
              theme.palette.mode === 'light' ? '0 12px 32px -8px rgba(1, 32, 66, 0.25)' : 'none',
            overflow: 'hidden',
          },
        },
      }}
    >
      <Box sx={{ px: 1.5, py: 1.25 }}>
        <Stack spacing={1}>
          <AnnotationBadgeRow annotation={annotation} />

          {annotation.commentCount > 0 ? (
            commentsQuery.isPending ? (
              <Stack spacing={0.5}>
                <Skeleton variant="text" width="90%" />
                <Skeleton variant="text" width="65%" />
              </Stack>
            ) : (
              <CommentMessage
                name={authorName}
                own={own}
                avatarUrl={own ? avatarUrl : avatarSrc(authorId)}
                body={firstComment?.body ?? annotation.firstComment ?? ''}
                createdAt={firstComment?.createdAt ?? annotation.createdAt}
                clampLines={3}
              />
            )
          ) : (
            <Typography variant="body2" color="text.secondary" sx={{ fontStyle: 'italic' }}>
              No comments yet.
            </Typography>
          )}

          <Typography variant="caption" color="text.secondary" sx={{ textAlign: 'right' }}>
            Click to open the thread
          </Typography>
        </Stack>
      </Box>
    </Popover>
  );
}
