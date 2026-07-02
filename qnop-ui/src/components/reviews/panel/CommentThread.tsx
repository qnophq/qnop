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
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useAddComment, useComments } from '../../../api/hooks/useComments';
import { useAuthStore } from '../../../stores/authStore';
import type { Notify } from '../../admin/layout/useToast';

interface CommentThreadProps {
  annotationId: string;
  notify: Notify;
}

const TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

/**
 * The annotation's discussion: a flat thread, oldest first (ADR-0011 — the
 * annotation owns the thread; accept/reject decisions live on the annotation,
 * not on comments). Participant names are not part of the annotation API yet,
 * so authorship is shown relative to the signed-in user.
 */
export function CommentThread({ annotationId, notify }: CommentThreadProps) {
  const userId = useAuthStore((state) => state.userId);
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

  return (
    <Stack spacing={1.5} sx={{ pt: 1 }}>
      {commentsQuery.isPending && (
        <Stack sx={{ alignItems: 'center', py: 1 }}>
          <CircularProgress size={18} />
        </Stack>
      )}
      {commentsQuery.isError && (
        <Typography variant="body2" color="error">
          Could not load the comments.
        </Typography>
      )}
      {commentsQuery.data?.comments.map((comment) => (
        <Stack key={comment.id} spacing={0.25}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline' }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {comment.authorId === userId ? 'You' : 'Participant'}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {TIME_FORMAT.format(new Date(comment.createdAt))}
            </Typography>
          </Stack>
          <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
            {comment.body}
          </Typography>
        </Stack>
      ))}
      {commentsQuery.data?.comments.length === 0 && (
        <Typography variant="body2" color="text.secondary">
          No comments yet.
        </Typography>
      )}
      <Stack spacing={1}>
        <TextField
          multiline
          minRows={2}
          size="small"
          placeholder="Add a comment"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          slotProps={{ htmlInput: { maxLength: 20000, 'aria-label': 'Add a comment' } }}
        />
        <Button
          variant="contained"
          size="small"
          onClick={submit}
          disabled={!draft.trim() || addComment.isPending}
          sx={{ alignSelf: 'flex-end' }}
        >
          Comment
        </Button>
      </Stack>
    </Stack>
  );
}
