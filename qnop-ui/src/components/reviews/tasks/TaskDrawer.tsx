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
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { ExternalLink, X } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { selectIsAdmin, useAuthStore } from '../../../stores/authStore';
import { tokens } from '../../../theme/tokens';
import type { Notify } from '../../admin/layout/useToast';
import type { BuildPermalink } from '../useReviewPermalink';
import { ResizableDrawer } from '../ResizableDrawer';
import { AnnotationHead } from '../panel/AnnotationHead';
import { CommentThread } from '../panel/CommentThread';
import { DismissControl } from '../panel/DismissControl';
import { ResolveBar } from '../panel/ResolveBar';
import {
  mayDismissAnnotation,
  mayReopenAnnotation,
  mayResolveAnnotation,
  useDismissWithFeedback,
  useReopenWithFeedback,
  useResolveWithFeedback,
} from '../panel/resolve';

interface TaskDrawerProps {
  annotation: AnnotationView | null;
  /** The previous visit (issue #307) — enables the thread's "new" divider. */
  previousSeenAt?: string | null;
  /** Tracker-style shorthand ("T-3") of the open annotation. */
  taskKey: string;
  notify: Notify;
  /** True once the review is FINALIZED/CANCELLED (issue #394): no reopening. */
  reviewClosed?: boolean;
  /** Thread participation policy (issue #413) — READ_ONLY suppresses foreign composers. */
  threadParticipation?: string;
  /** The review owner (issue #413) — the owner may always comment under any policy. */
  ownerId?: string;
  onClose: () => void;
  /** Jumps to the review page with this annotation active (deep link). */
  onShowInDocument: (annotationId: string) => void;
  /** Builds annotation/comment permalinks (issue #412) — enables the copy affordances. */
  buildPermalink?: BuildPermalink;
}

/**
 * The task's issue-detail drawer (issue #393, prototype `reviewhub.jsx`): a
 * slim tracker header (task key, deep link, close), then the discussion led by
 * the same author-fronted `AnnotationHead` the document view opens with
 * (issue #403 follow-up) — the thread starter, badges, quoted passage and
 * opening text read identically on every surface — followed by the full
 * `CommentThread` and the author's Resolve bar. Also the keyboard path for
 * resolving (the board's drag-to-Resolved is a pointer shortcut).
 */
export function TaskDrawer({
  annotation,
  previousSeenAt = null,
  taskKey,
  notify,
  reviewClosed = false,
  threadParticipation = 'OPEN',
  ownerId,
  onClose,
  onShowInDocument,
  buildPermalink,
}: TaskDrawerProps) {
  const userId = useAuthStore((state) => state.userId);
  const viewerIsAdmin = useAuthStore(selectIsAdmin);
  const { resolveWith, isPending } = useResolveWithFeedback(notify);
  const { reopenWith } = useReopenWithFeedback(notify);
  const { dismissWith, isPending: dismissing } = useDismissWithFeedback(notify);

  if (!annotation) return null;
  // READ_ONLY policy (issue #413): only the author and the owner may reply.
  const policyReadOnly =
    threadParticipation !== 'OPEN' &&
    annotation.authorId !== userId &&
    !(ownerId != null && userId === ownerId);
  return (
    <ResizableDrawer
      open
      onClose={onClose}
      storageKey="qnop-task-drawer-width"
      defaultWidth={460}
      handleAriaLabel="Resize the task drawer"
      handleTestId="task-drawer-resize-handle"
      drawerTestId="task-drawer"
    >
      <Stack sx={{ height: '100%' }}>
        {/* Slim tracker header — the discussion below leads with the head. */}
        <Box sx={{ px: 2, py: 1, borderBottom: '1px solid', borderColor: 'divider' }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
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
            <Box sx={{ flex: 1 }} />
            <Button
              size="small"
              variant="text"
              startIcon={<ExternalLink size={13} />}
              onClick={() => onShowInDocument(annotation.id)}
            >
              Show in document
            </Button>
            <IconButton size="small" onClick={onClose} aria-label="Close task">
              <X size={16} />
            </IconButton>
          </Stack>
        </Box>

        <Box sx={{ flex: 1, overflowY: 'auto', px: 2, py: 1.5 }}>
          {/* The root post, exactly as the document view presents it: the
              author leading, badges, the quoted passage and the opening text
              (issue #403 follow-up). It carries the annotation copy-link in
              its author row. */}
          <AnnotationHead
            annotation={annotation}
            permalinkUrl={buildPermalink?.(annotation.id)}
            notify={notify}
          />
          <CommentThread
            annotationId={annotation.id}
            documentId={annotation.documentId}
            notify={notify}
            policyReadOnly={policyReadOnly}
            closed={annotation.status !== AnnotationStatus.Open}
            dismissed={annotation.status === AnnotationStatus.Dismissed}
            onReopen={
              !reviewClosed && mayReopenAnnotation(annotation, userId, viewerIsAdmin)
                ? () => reopenWith(annotation)
                : undefined
            }
            previousSeenAt={previousSeenAt}
            skipOpener
            buildPermalink={buildPermalink}
          />
        </Box>

        {mayResolveAnnotation(annotation, userId) && (
          <Box sx={{ borderTop: '1px solid', borderColor: 'divider', px: 1, py: 0.5 }}>
            <ResolveBar disabled={isPending} onResolve={(note) => resolveWith(annotation, note)} />
          </Box>
        )}
        {!reviewClosed && mayDismissAnnotation(annotation, userId, ownerId, viewerIsAdmin) && (
          // The owner/admin escape hatch (issue #408) — subordinate to the
          // author's Resolve; never both, the author is excluded.
          <Box sx={{ borderTop: '1px solid', borderColor: 'divider', px: 1, py: 0.5 }}>
            <DismissControl
              disabled={dismissing}
              onDismiss={(justification) => dismissWith(annotation, justification)}
            />
          </Box>
        )}
      </Stack>
    </ResizableDrawer>
  );
}
