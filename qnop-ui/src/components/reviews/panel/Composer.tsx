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
import Button from '@mui/material/Button';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import type { Anchor } from '../../../api/generated';
import { AnnotationPriority, AnnotationType } from '../../../api/generated';
import { isSubmitShortcut, submitShortcutLabel } from '../../../utils/platform';
import { PRIORITY_CUES, TYPE_CUES } from '../tasks/tasksModel';

/**
 * The composer for a freshly drawn anchor — rendered in the panel, and in
 * focus mode inside the overlay card. Also runs anchor-free (`pendingAnchor`
 * null) for a document-scoped remark (issue #395), where the surrounding
 * dialog owns the heading, so it renders `frameless` and drops its own frame
 * and passage line. The first comment is mandatory (issue #301) — an
 * annotation without text must not exist, so creating stays disabled (button
 * and submit shortcut) until the trimmed comment is non-empty.
 */
export function Composer({
  pendingAnchor,
  creating,
  onCreate,
  onCancel,
  frameless = false,
}: {
  /** The drawn region, or null for a document-scoped annotation (issue #395). */
  pendingAnchor: Anchor | null;
  creating: boolean;
  onCreate: (comment: string, type?: AnnotationType, priority?: AnnotationPriority) => void;
  onCancel: () => void;
  /** Drops the card frame and heading/passage line — for hosting inside a dialog. */
  frameless?: boolean;
}) {
  const theme = useTheme();
  const [comment, setComment] = useState('');
  const [type, setType] = useState<AnnotationType | ''>('');
  const [priority, setPriority] = useState<AnnotationPriority | ''>('');
  const canCreate = !creating && comment.trim().length > 0;
  const quote = pendingAnchor?.textQuote?.quote;
  const create = () => onCreate(comment, type || undefined, priority || undefined);
  return (
    <Paper
      variant={frameless ? 'elevation' : 'outlined'}
      elevation={0}
      sx={{ p: frameless ? 0 : 1.5, bgcolor: 'transparent' }}
      data-testid="annotation-composer"
    >
      <Stack spacing={1}>
        {!frameless && <Typography variant="subtitle2">New annotation</Typography>}
        {!frameless && pendingAnchor && (
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
        )}
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
              if (canCreate) create();
            }
          }}
          slotProps={{ htmlInput: { maxLength: 20000, 'aria-label': 'Annotation comment' } }}
        />
        {/* Optional classification (issue #392) in the system's task language. */}
        <Stack direction="row" spacing={1}>
          <TextField
            select
            size="small"
            label="Type"
            value={type}
            onChange={(event) => setType(event.target.value as AnnotationType | '')}
            sx={{ flex: 1 }}
            slotProps={{ htmlInput: { 'aria-label': 'Annotation type' } }}
          >
            <MenuItem value="">
              <Typography component="span" variant="body2" color="text.secondary">
                None
              </Typography>
            </MenuItem>
            {Object.values(AnnotationType).map((value) => {
              const cue = TYPE_CUES[value];
              const CueIcon = cue.icon;
              return (
                <MenuItem key={value} value={value}>
                  <Stack
                    direction="row"
                    spacing={0.75}
                    sx={{ alignItems: 'center', color: cue.color(theme) }}
                  >
                    <CueIcon size={13} aria-hidden />
                    <Typography component="span" variant="body2">
                      {cue.label}
                    </Typography>
                  </Stack>
                </MenuItem>
              );
            })}
          </TextField>
          <TextField
            select
            size="small"
            label="Priority"
            value={priority}
            onChange={(event) => setPriority(event.target.value as AnnotationPriority | '')}
            sx={{ flex: 1 }}
            slotProps={{ htmlInput: { 'aria-label': 'Annotation priority' } }}
          >
            <MenuItem value="">
              <Typography component="span" variant="body2" color="text.secondary">
                None
              </Typography>
            </MenuItem>
            {Object.values(AnnotationPriority).map((value) => {
              const cue = PRIORITY_CUES[value];
              return (
                <MenuItem key={value} value={value}>
                  <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                    <Box
                      sx={{ width: 7, height: 7, borderRadius: '50%', bgcolor: cue.color(theme) }}
                      aria-hidden
                    />
                    <Typography component="span" variant="body2">
                      {cue.label}
                    </Typography>
                  </Stack>
                </MenuItem>
              );
            })}
          </TextField>
        </Stack>
        <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
          <Button size="small" variant="contained" onClick={create} disabled={!canCreate}>
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
