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
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { ReactionGroup } from '../../../api/generated';
import { shortRelativeTime } from '../../../utils/relativeTime';
import type { Notify } from '../../admin/layout/useToast';
import { UserAvatar } from '../../shell/UserAvatar';
import { Markdown } from '../markdown/Markdown';
import { CopyLinkButton } from '../permalink/CopyLinkButton';
import { AddReactionButton } from '../reactions/AddReactionButton';
import { ReactionBar } from '../reactions/ReactionBar';

/** Shared with the thread's rail and composer, so the columns stay aligned. */
export const AVATAR_SIZE = 28;

const TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

interface CommentMessageProps {
  /** The display name; the message prints "You" for the viewer's own. */
  name: string;
  own: boolean;
  avatarUrl: string | null;
  body: string;
  createdAt: string;
  /** Clamp the body to N lines (the hover preview); unset shows everything. */
  clampLines?: number;
  /** A stable DOM id on the message root — the scroll/pulse target of a comment permalink (#412). */
  domId?: string;
  /** The comment's permalink; with `notify` it renders the hover copy affordance (issue #412). */
  permalinkUrl?: string;
  notify?: Notify;
  /** The comment's grouped emoji reactions (issue #410). */
  reactions?: ReactionGroup[];
  /** Toggles the viewer's reaction; its presence renders the reaction affordances. */
  onToggleReaction?: (emoji: string, reacted: boolean) => void;
}

/**
 * One comment in the discussion, in Slack's message anatomy (issue #445, was
 * a Facebook-style bubble until then): avatar beside a full-width column with
 * the header line — bold author, muted relative timestamp (full date in the
 * tooltip) and the copy-link affordance revealed on hover — above the Markdown
 * body. Full width + generous line height is what makes long, formatted
 * comments readable. Shared by the thread and the mark's hover preview, so a
 * comment reads identically everywhere.
 */
export function CommentMessage({
  name,
  own,
  avatarUrl,
  body,
  createdAt,
  clampLines,
  domId,
  permalinkUrl,
  notify,
  reactions = [],
  onToggleReaction,
}: CommentMessageProps) {
  return (
    <Stack
      id={domId}
      direction="row"
      spacing={1.25}
      sx={{
        alignItems: 'flex-start',
        borderRadius: '8px',
        // The copy-link affordance stays quiet until the message is hovered or
        // focused; pointer-less (touch) devices see it permanently.
        '& .comment-hover-actions': {
          opacity: 0,
          transition: 'opacity 120ms ease',
          '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
          '@media (hover: none)': { opacity: 1 },
        },
        '&:hover .comment-hover-actions, &:focus-within .comment-hover-actions': { opacity: 1 },
      }}
    >
      <Box sx={{ position: 'relative', zIndex: 1 }}>
        <UserAvatar name={name} size={AVATAR_SIZE} imageUrl={own ? avatarUrl : null} />
      </Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', minWidth: 0, mb: 0.25 }}>
          <Typography
            component="span"
            noWrap
            sx={{ fontWeight: 700, fontSize: '0.875rem', lineHeight: 1.4 }}
          >
            {own ? 'You' : name}
          </Typography>
          <Typography
            component="span"
            variant="caption"
            title={TIME_FORMAT.format(new Date(createdAt))}
            sx={{ color: 'text.secondary', flexShrink: 0 }}
          >
            {shortRelativeTime(createdAt)}
          </Typography>
          {(onToggleReaction || (permalinkUrl && notify)) && (
            <Box className="comment-hover-actions" sx={{ display: 'flex', alignItems: 'center' }}>
              {permalinkUrl && notify && (
                <CopyLinkButton url={permalinkUrl} notify={notify} label="Copy link to comment" />
              )}
              {onToggleReaction && (
                <AddReactionButton
                  onPick={(emoji) =>
                    onToggleReaction(
                      emoji,
                      reactions.find((group) => group.emoji === emoji)?.reactedByMe ?? false,
                    )
                  }
                />
              )}
            </Box>
          )}
        </Stack>
        {/* The body renders as sanitised Markdown (issue #427); the hover
            preview passes clampLines to cap its height. */}
        <Markdown clampLines={clampLines}>{body}</Markdown>
        {onToggleReaction && reactions.length > 0 && (
          <Box sx={{ mt: 0.75 }}>
            <ReactionBar reactions={reactions} onToggle={onToggleReaction} />
          </Box>
        )}
      </Box>
    </Stack>
  );
}
