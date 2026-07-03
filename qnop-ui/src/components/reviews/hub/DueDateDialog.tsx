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
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useUpdateDueDate } from '../../../api/hooks/useReviews';
import type { ToastSeverity } from '../../admin/layout/useToast';
import { apiErrorMessage } from '../../../utils/apiError';
import { DueDatePicker } from '../DueDatePicker';

interface DueDateDialogProps {
  documentId: string;
  open: boolean;
  onClose: () => void;
  /** The current due date (ISO instant) or null. */
  dueAt: string | null;
  notify: (message: string, severity?: ToastSeverity) => void;
}

/**
 * The owner-only due-date editor (issue #295). Editing allows any date — including
 * the past — so a deadline can be corrected after the fact; clearing removes it.
 * Mirrors {@link ParticipantsDialog} as the review hub's small owner affordance.
 */
export function DueDateDialog({ documentId, open, onClose, dueAt, notify }: DueDateDialogProps) {
  const [value, setValue] = useState<string | null>(dueAt);
  const [wasOpen, setWasOpen] = useState(open);
  const update = useUpdateDueDate(documentId);

  // Reset the draft to the live value each time the dialog transitions to open —
  // the recommended "adjust state during render" pattern rather than an effect.
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) setValue(dueAt);
  }

  const save = () => {
    update.mutate(value, {
      onSuccess: () => {
        notify(value ? 'Due date updated.' : 'Due date cleared.');
        onClose();
      },
      onError: (error) =>
        notify(apiErrorMessage(error, 'The due date could not be updated.'), 'error'),
    });
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Review due date</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            An optional, informational deadline. Overdue open reviews are flagged in the overview,
            never blocked or cancelled.
          </Typography>
          <DueDatePicker value={value} onChange={setValue} disablePast={false} />
        </Stack>
      </DialogContent>
      <DialogActions>
        {dueAt && (
          <Button
            color="inherit"
            onClick={() => setValue(null)}
            disabled={update.isPending}
            sx={{ mr: 'auto' }}
          >
            Clear
          </Button>
        )}
        <Button color="inherit" onClick={onClose} disabled={update.isPending}>
          Cancel
        </Button>
        <Button variant="contained" onClick={save} disabled={update.isPending}>
          {update.isPending ? 'Saving…' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
