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
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { SendHorizontal } from 'lucide-react';
import { useAddComment, useComments } from '../../../api/hooks/useComments';
import { useAuthStore } from '../../../stores/authStore';
import type { Notify } from '../../admin/layout/useToast';
import { UserAvatar } from '../../shell/UserAvatar';

const AVATAR_SIZE = 26;
/** Centre of the avatar column — where the thread line runs. */
const LINE_LEFT = AVATAR_SIZE / 2 - 1;

interface CommentThreadProps {
  annotationId: string;
  notify: Notify;
}

const TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

/**
 * The annotation's discussion as a social-style thread (ADR-0011 — the
 * annotation owns the thread): every comment is an avatar on a shared
 * timeline rail with a speech bubble, the composer continues the same rail —
 * the annotation card above is the root post. Participant names are not part
 * of the annotation API yet, so authorship is shown relative to the signed-in
 * user.
 */
export function CommentThread({ annotationId, notify }: CommentThreadProps) {
  const theme = useTheme();
  const userId = useAuthStore((state) => state.userId);
  const displayName = useAuthStore((state) => state.displayName);
  const avatarUrl = useAuthStore((state) => state.avatarUrl);
  const commentsQuery = useComments(annotationId);
  const addComment = useAddComment(annotationId);
  const [draft, setDraft] = useState('');

  const submit = () => {
    const body = draft.trim();
    if (!body) return;
    addComment.mutate(body, {
      onSuccess: () => setDraft(''),
      onError: () => notify('Could not add the comment.', 'error'),
    });
  };

  const comments = commentsQuery.data?.comments ?? [];

  return (
    <Box sx={{ position: 'relative', mt: 0.5, ml: 1.5, pl: 0 }}>
      {/* The timeline rail connecting the annotation card to its thread. */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          left: LINE_LEFT,
          top: -6,
          bottom: 18,
          width: 2,
          borderRadius: 1,
          bgcolor: theme.palette.divider,
        }}
      />
      <Stack spacing={1.25} sx={{ pt: 1 }}>
        {commentsQuery.isPending && (
          <Stack sx={{ alignItems: 'center', py: 1 }}>
            <CircularProgress size={18} aria-label="Loading comments" />
          </Stack>
        )}
        {commentsQuery.isError && (
          <Typography variant="body2" color="error" sx={{ pl: 4.5 }}>
            Could not load the comments.
          </Typography>
        )}
        {comments.map((comment) => {
          const own = comment.authorId === userId;
          const name = own ? (displayName ?? 'You') : 'Participant';
          return (
            <Stack key={comment.id} direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
              <Box sx={{ position: 'relative', zIndex: 1, pt: 0.25 }}>
                <UserAvatar name={name} size={AVATAR_SIZE} imageUrl={own ? avatarUrl : null} />
              </Box>
              <Box
                sx={{
                  flex: 1,
                  minWidth: 0,
                  bgcolor: theme.qnop.surface2,
                  borderRadius: '3px 8px 8px 8px',
                  px: 1.25,
                  py: 0.75,
                }}
              >
                <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline' }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {own ? 'You' : name}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {TIME_FORMAT.format(new Date(comment.createdAt))}
                  </Typography>
                </Stack>
                <Typography
                  variant="body2"
                  sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', mt: 0.25 }}
                >
                  {comment.body}
                </Typography>
              </Box>
            </Stack>
          );
        })}
        {!commentsQuery.isPending && !commentsQuery.isError && comments.length === 0 && (
          <Typography variant="body2" color="text.secondary" sx={{ pl: 4.5 }}>
            No comments yet. Start the discussion.
          </Typography>
        )}
        {/* Composer continues the thread rail with the signed-in user's avatar. */}
        <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
          <Box sx={{ position: 'relative', zIndex: 1, pt: 0.5 }}>
            <UserAvatar name={displayName ?? 'You'} size={AVATAR_SIZE} imageUrl={avatarUrl} />
          </Box>
          <TextField
            multiline
            minRows={3}
            size="small"
            fullWidth
            placeholder="Add a comment"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            slotProps={{
              htmlInput: { maxLength: 20000, 'aria-label': 'Add a comment' },
              input: {
                sx: { borderRadius: 1, bgcolor: 'background.paper' },
                endAdornment: (
                  <IconButton
                    size="small"
                    aria-label="Comment"
                    color="primary"
                    onClick={submit}
                    disabled={!draft.trim() || addComment.isPending}
                    sx={{ alignSelf: 'flex-end' }}
                  >
                    <SendHorizontal size={16} />
                  </IconButton>
                ),
              },
            }}
          />
        </Stack>
      </Stack>
    </Box>
  );
}
