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
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import type { Anchor } from '../../../api/generated';
import { isSubmitShortcut, submitShortcutLabel } from '../../../utils/platform';

/**
 * The composer for a freshly drawn anchor — rendered in the panel, and in
 * focus mode inside the overlay card. The first comment is mandatory (issue
 * #301) — an annotation without text must not exist, so creating stays
 * disabled (button and submit shortcut) until the trimmed comment is
 * non-empty.
 */
export function Composer({
  pendingAnchor,
  creating,
  onCreate,
  onCancel,
}: {
  pendingAnchor: Anchor;
  creating: boolean;
  onCreate: (comment: string) => void;
  onCancel: () => void;
}) {
  const [comment, setComment] = useState('');
  const canCreate = !creating && comment.trim().length > 0;
  const quote = pendingAnchor.textQuote?.quote;
  return (
    <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="annotation-composer">
      <Stack spacing={1}>
        <Typography variant="subtitle2">New annotation</Typography>
        <Typography
          variant="body2"
          color="text.secondary"
          sx={{
            fontStyle: quote ? 'italic' : 'normal',
            display: '-webkit-box',
            WebkitLineClamp: 3,
            WebkitBoxOrient: 'vertical',
            overflow: 'hidden',
          }}
        >
          {quote ? `“${quote}”` : `Region on page ${pendingAnchor.region.surfaceIndex + 1}`}
        </Typography>
        <TextField
          multiline
          minRows={5}
          size="small"
          required
          placeholder="Add a comment"
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          onKeyDown={(event) => {
            if (isSubmitShortcut(event)) {
              event.preventDefault();
              if (canCreate) onCreate(comment);
            }
          }}
          slotProps={{ htmlInput: { maxLength: 20000, 'aria-label': 'Annotation comment' } }}
        />
        <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
          <Button
            size="small"
            variant="contained"
            onClick={() => onCreate(comment)}
            disabled={!canCreate}
          >
            Create annotation ({submitShortcutLabel()})
          </Button>
          <Button size="small" onClick={onCancel} disabled={creating}>
            Cancel
          </Button>
        </Stack>
      </Stack>
    </Paper>
  );
}
