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
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { Trash2 } from 'lucide-react';
import { useDeleteReview } from '../../api/hooks/useReviews';
import { apiErrorMessage } from '../../utils/apiError';

interface DeleteReviewDialogProps {
  documentId: string;
  title: string;
  open: boolean;
  onClose: () => void;
  /** What the deletion will take with it, so the reader can weigh it. */
  versionCount?: number;
  annotationCount?: number;
  onDeleted: () => void;
}

/** Plural-aware "3 versions" / "1 version". */
function count(value: number, noun: string): string {
  return `${value} ${noun}${value === 1 ? '' : 's'}`;
}

/**
 * Confirming the deletion of a review (issue #421).
 *
 * <p>The title has to be typed. That is heavier than a confirm button on purpose: this destroys
 * other people's annotations and discussions along with the document, there is no undo and no
 * recycle bin, and the admins who can do it are the same people working through a list of
 * look-alike reviews. A misclick has to be impossible, not merely unlikely.
 *
 * <p>It also says what goes, in numbers. "This cannot be undone" is a warning; "3 versions and 12
 * annotations" is information.
 */
export function DeleteReviewDialog({
  documentId,
  title,
  open,
  onClose,
  versionCount,
  annotationCount,
  onDeleted,
}: DeleteReviewDialogProps) {
  const [typed, setTyped] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [wasOpen, setWasOpen] = useState(open);
  const remove = useDeleteReview(documentId);

  // Reset the draft each time it opens, so a previous attempt's typing can never
  // arm the button for a different review.
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setTyped('');
      setError(null);
    }
  }

  const confirmed = typed.trim() === title.trim();
  const pending = remove.isPending;

  const scope = [
    versionCount === undefined ? null : count(versionCount, 'version'),
    annotationCount === undefined ? null : count(annotationCount, 'annotation'),
  ].filter(Boolean);

  const handleDelete = () => {
    remove.mutate(undefined, {
      onSuccess: onDeleted,
      onError: (deleteError) =>
        setError(apiErrorMessage(deleteError, 'The review could not be deleted.')),
    });
  };

  return (
    <Dialog open={open} onClose={pending ? undefined : onClose} fullWidth maxWidth="xs">
      <DialogTitle>Delete this review?</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <Typography variant="body2" color="text.secondary">
            <strong>{title}</strong>
            {scope.length > 0 && <> and its {scope.join(' and ')}</>} will be removed permanently,
            for everyone. This cannot be undone.
          </Typography>
          <Typography variant="body2" color="text.secondary">
            To archive it instead — reversible, and it keeps the record — close this and use
            Archive.
          </Typography>
          <TextField
            label="Type the review title to confirm"
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            disabled={pending}
            autoComplete="off"
            fullWidth
            size="small"
            slotProps={{ htmlInput: { 'data-testid': 'delete-confirm-input' } }}
          />
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={pending} color="inherit">
          Cancel
        </Button>
        <Button
          variant="contained"
          color="error"
          startIcon={<Trash2 size={16} />}
          disabled={!confirmed || pending}
          onClick={handleDelete}
        >
          {pending ? 'Deleting…' : 'Delete permanently'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
