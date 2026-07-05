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
import { useTheme } from '@mui/material/styles';
import { shortRelativeTime } from '../../../utils/relativeTime';
import { UserAvatar } from '../../shell/UserAvatar';

const AVATAR_SIZE = 26;

const TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

interface CommentBubbleProps {
  /** The display name; the bubble prints "You" for the viewer's own. */
  name: string;
  own: boolean;
  avatarUrl: string | null;
  body: string;
  createdAt: string;
  /** Clamp the body to N lines (the hover preview); unset shows everything. */
  clampLines?: number;
}

/**
 * One comment in the social anatomy (issue #403): avatar, a softly rounded
 * bubble carrying the bold author name above the text, and the compact
 * relative timestamp in the meta line underneath (full date in the tooltip).
 * Shared by the thread and the mark's hover preview, so a comment reads
 * identically everywhere.
 */
export function CommentBubble({
  name,
  own,
  avatarUrl,
  body,
  createdAt,
  clampLines,
}: CommentBubbleProps) {
  const theme = useTheme();
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
      <Box sx={{ position: 'relative', zIndex: 1, pt: 0.25 }}>
        <UserAvatar name={name} size={AVATAR_SIZE} imageUrl={own ? avatarUrl : null} />
      </Box>
      <Box sx={{ minWidth: 0, maxWidth: '100%' }}>
        <Box
          sx={{
            display: 'inline-block',
            maxWidth: '100%',
            bgcolor: theme.qnop.surface2,
            borderRadius: '4px 10px 10px 10px',
            px: 1.5,
            py: 0.75,
          }}
        >
          <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.3 }}>
            {own ? 'You' : name}
          </Typography>
          <Typography
            variant="body2"
            sx={{
              whiteSpace: 'pre-wrap',
              overflowWrap: 'anywhere',
              ...(clampLines !== undefined && {
                display: '-webkit-box',
                WebkitLineClamp: clampLines,
                WebkitBoxOrient: 'vertical',
                overflow: 'hidden',
                whiteSpace: 'normal',
              }),
            }}
          >
            {body}
          </Typography>
        </Box>
        <Typography
          variant="caption"
          title={TIME_FORMAT.format(new Date(createdAt))}
          sx={{ display: 'block', pl: 1.5, mt: 0.25, fontWeight: 600, color: 'text.secondary' }}
        >
          {shortRelativeTime(createdAt)}
        </Typography>
      </Box>
    </Stack>
  );
}
