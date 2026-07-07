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

import Dialog from '@mui/material/Dialog';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { FileText } from 'lucide-react';
import type { AnnotationPriority, AnnotationType } from '../../../api/generated';
import { useCreateAnnotation } from '../../../api/hooks/useAnnotations';
import { apiErrorCode } from '../../../utils/apiError';
import type { Notify } from '../../admin/layout/useToast';
import { Composer } from '../panel/Composer';

interface NewTaskDialogProps {
  open: boolean;
  documentId: string;
  /** The latest version — the authoring context; a document-scoped task gets no placement on it. */
  versionNumber: number;
  notify: Notify;
  onClose: () => void;
}

/**
 * Creates a document-scoped ("global") annotation (issue #395): a general
 * remark that applies to the whole document and needs no text selection,
 * raisable from the tasks, document and focus views. It reuses the panel's
 * `Composer` (frameless) so the comment + optional type/priority read
 * identically to a located annotation; the request simply carries no anchor,
 * which the server takes as document-scoped.
 */
export function NewTaskDialog({
  open,
  documentId,
  versionNumber,
  notify,
  onClose,
}: NewTaskDialogProps) {
  const createAnnotation = useCreateAnnotation(documentId);

  const handleCreate = (comment: string, type?: AnnotationType, priority?: AnnotationPriority) => {
    // No anchor → the server creates no placement: a document-scoped annotation (issue #395).
    createAnnotation.mutate(
      { versionNumber, comment, type, priority },
      {
        onSuccess: () => {
          notify('Annotation created.');
          onClose();
        },
        onError: (error) =>
          notify(
            apiErrorCode(error) === 'REVIEW_CLOSED'
              ? 'This review is closed — no new annotations can be added.'
              : 'Could not create the annotation.',
            'error',
          ),
      },
    );
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <FileText size={18} aria-hidden />
          New global annotation
        </Stack>
      </DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          A general remark that applies to the whole document — it is not pinned to a passage and
          stays valid on every version.
        </Typography>
        <Composer
          pendingAnchor={null}
          frameless
          creating={createAnnotation.isPending}
          onCreate={handleCreate}
          onCancel={onClose}
        />
      </DialogContent>
    </Dialog>
  );
}
