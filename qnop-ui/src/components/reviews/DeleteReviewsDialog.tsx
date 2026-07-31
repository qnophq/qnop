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
import LinearProgress from '@mui/material/LinearProgress';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { Trash2 } from 'lucide-react';
import { documentsApi } from '../../api/config';
import { useQueryClient } from '@tanstack/react-query';
import { documentKeys } from '../../api/hooks/useDocuments';
import { reviewKeys } from '../../api/hooks/useReviews';
import { apiErrorMessage } from '../../utils/apiError';

/** What the caller must type to arm the button. */
export const BULK_DELETE_PHRASE = 'delete';

export interface DeletableReview {
  id: string;
  title: string;
}

interface DeleteReviewsDialogProps {
  reviews: DeletableReview[];
  open: boolean;
  onClose: () => void;
  /** Called with how many actually went, so the caller can report and re-select. */
  onDeleted: (deletedIds: string[]) => void;
}

/**
 * Confirming the deletion of several reviews at once (issue #421).
 *
 * <p>The single-review dialog has the admin type that review's title. Typing a dozen titles would
 * be theatre, so this one names every review it will destroy and asks for one deliberate word. The
 * list is the part that matters: a selection made across a filtered, paged listing is easy to
 * misjudge, and seeing the titles is what catches the row that should not be there.
 *
 * <p>There is no bulk endpoint behind it. Each review is deleted on its own, so one refusal — a
 * review someone removed in the meantime, a permission that changed — costs that review and not the
 * rest, and the dialog can say exactly which ones survived.
 */
export function DeleteReviewsDialog({
  reviews,
  open,
  onClose,
  onDeleted,
}: DeleteReviewsDialogProps) {
  const queryClient = useQueryClient();
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(0);
  const [failures, setFailures] = useState<string[]>([]);
  const [wasOpen, setWasOpen] = useState(open);

  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setTyped('');
      setBusy(false);
      setDone(0);
      setFailures([]);
    }
  }

  const confirmed = typed.trim().toLowerCase() === BULK_DELETE_PHRASE;

  const handleDelete = async () => {
    setBusy(true);
    setFailures([]);
    const deleted: string[] = [];
    const failed: string[] = [];
    for (const review of reviews) {
      try {
        await documentsApi.deleteDocument({ documentId: review.id });
        deleted.push(review.id);
      } catch (error) {
        failed.push(`${review.title}: ${apiErrorMessage(error, 'could not be deleted')}`);
      }
      setDone((count) => count + 1);
    }
    // One invalidation at the end rather than per review: the list would
    // otherwise refetch a dozen times while the dialog is still working.
    deleted.forEach((id) => queryClient.removeQueries({ queryKey: documentKeys.detail(id) }));
    queryClient.invalidateQueries({ queryKey: reviewKeys.all });
    setBusy(false);
    if (failed.length > 0) {
      // Kept open on a partial failure: closing would report success for a job
      // that was only mostly done.
      setFailures(failed);
      onDeleted(deleted);
      return;
    }
    onDeleted(deleted);
    onClose();
  };

  return (
    <Dialog open={open} onClose={busy ? undefined : onClose} fullWidth maxWidth="sm">
      <DialogTitle>
        Delete {reviews.length} review{reviews.length === 1 ? '' : 's'}?
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <Typography variant="body2" color="text.secondary">
            These will be removed permanently, for everyone — every version, annotation and
            discussion in them. This cannot be undone. To keep the record instead, close this and
            archive them.
          </Typography>

          <Paper variant="outlined" sx={{ maxHeight: 220, overflowY: 'auto' }}>
            <List dense disablePadding data-testid="bulk-delete-list">
              {reviews.map((review) => (
                <ListItem key={review.id} divider>
                  <ListItemText
                    primary={review.title}
                    slotProps={{ primary: { variant: 'body2', noWrap: true } }}
                  />
                </ListItem>
              ))}
            </List>
          </Paper>

          <TextField
            label={`Type "${BULK_DELETE_PHRASE}" to confirm`}
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            disabled={busy}
            autoComplete="off"
            fullWidth
            size="small"
            slotProps={{ htmlInput: { 'data-testid': 'bulk-delete-confirm-input' } }}
          />

          {busy && (
            <Stack spacing={0.75}>
              <LinearProgress
                variant="determinate"
                value={Math.round((done / reviews.length) * 100)}
              />
              <Typography variant="caption" color="text.secondary">
                Deleting… {done} of {reviews.length}
              </Typography>
            </Stack>
          )}

          {failures.length > 0 && (
            <Alert severity="error">
              {/* Named, not counted: "3 failed" leaves the reader to work out which. */}
              <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>
                {failures.length} of {reviews.length} could not be deleted
              </Typography>
              {failures.map((failure) => (
                <Typography key={failure} variant="caption" component="div">
                  {failure}
                </Typography>
              ))}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={busy} color="inherit">
          {failures.length > 0 ? 'Close' : 'Cancel'}
        </Button>
        <Button
          variant="contained"
          color="error"
          startIcon={<Trash2 size={16} />}
          disabled={!confirmed || busy || reviews.length === 0}
          onClick={handleDelete}
        >
          {busy ? 'Deleting…' : `Delete ${reviews.length} permanently`}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
