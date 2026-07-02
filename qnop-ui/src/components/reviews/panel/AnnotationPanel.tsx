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
import Collapse from '@mui/material/Collapse';
import Divider from '@mui/material/Divider';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { NotebookPen } from 'lucide-react';
import type { Anchor, AnnotationView } from '../../../api/generated';
import type { Notify } from '../../admin/layout/useToast';
import { SectionCard } from '../../admin/layout/SectionCard';
import { compareAnnotationsByPosition } from '../viewer/anchoring';
import { AnnotationListItem } from './AnnotationListItem';
import { CommentThread } from './CommentThread';

interface AnnotationPanelProps {
  annotations: AnnotationView[];
  activeAnnotationId: string | null;
  onSelect: (annotationId: string | null) => void;
  /** The drawn-but-not-yet-created anchor; non-null opens the composer. */
  pendingAnchor: Anchor | null;
  creating: boolean;
  onCreate: (comment: string) => void;
  onCancelPending: () => void;
  canAnnotate: boolean;
  notify: Notify;
}

/** The composer for a freshly drawn anchor: optional first comment, then create. */
function Composer({
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
          minRows={2}
          size="small"
          placeholder="Add a comment (optional)"
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          slotProps={{ htmlInput: { maxLength: 20000, 'aria-label': 'Annotation comment' } }}
        />
        <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
          <Button size="small" onClick={onCancel} disabled={creating}>
            Cancel
          </Button>
          <Button
            size="small"
            variant="contained"
            onClick={() => onCreate(comment)}
            disabled={creating}
          >
            Create annotation
          </Button>
        </Stack>
      </Stack>
    </Paper>
  );
}

/**
 * The right-hand review panel: the composer for a pending mark, then all
 * annotations of the viewed version ordered by document position; annotations
 * without a placement on this version (orphaned/failed, ADR-0009) are listed
 * separately at the end. The active annotation expands into its comment thread.
 */
export function AnnotationPanel({
  annotations,
  activeAnnotationId,
  onSelect,
  pendingAnchor,
  creating,
  onCreate,
  onCancelPending,
  canAnnotate,
  notify,
}: AnnotationPanelProps) {
  const sorted = [...annotations].sort(compareAnnotationsByPosition);
  const placed = sorted.filter((annotation) => annotation.anchor);
  const unplaced = sorted.filter((annotation) => !annotation.anchor);

  const renderItem = (annotation: AnnotationView) => {
    const active = annotation.id === activeAnnotationId;
    return (
      <Stack key={annotation.id} spacing={0}>
        <AnnotationListItem
          annotation={annotation}
          active={active}
          onClick={() => onSelect(active ? null : annotation.id)}
        />
        <Collapse in={active} unmountOnExit>
          <CommentThread annotationId={annotation.id} notify={notify} />
        </Collapse>
      </Stack>
    );
  };

  return (
    <SectionCard
      icon={NotebookPen}
      title={`Annotations (${annotations.length})`}
      description="Marks and their discussion on this version."
    >
      <Stack spacing={1.5}>
        {pendingAnchor && (
          <Composer
            pendingAnchor={pendingAnchor}
            creating={creating}
            onCreate={onCreate}
            onCancel={onCancelPending}
          />
        )}
        {annotations.length === 0 && !pendingAnchor && (
          <Typography variant="body2" color="text.secondary">
            No annotations yet.
            {canAnnotate && ' Select text or draw a region on the document to add one.'}
          </Typography>
        )}
        {placed.map(renderItem)}
        {unplaced.length > 0 && (
          <>
            <Divider>
              <Typography variant="caption" color="text.secondary">
                Not placed on this version
              </Typography>
            </Divider>
            {unplaced.map(renderItem)}
          </>
        )}
      </Stack>
    </SectionCard>
  );
}
