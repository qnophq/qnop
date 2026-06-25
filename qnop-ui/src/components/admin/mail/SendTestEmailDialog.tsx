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

import { useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import type { SendTestEmailResponse } from '../../../api/generated';
import { useSendTestEmail } from '../../../api/hooks/useMailTemplates';
import { apiErrorMessage } from '../../../utils/apiError';

interface SendTestEmailDialogProps {
  open: boolean;
  onClose: () => void;
}

const SEVERITY: Record<SendTestEmailResponse['status'], 'success' | 'warning' | 'error'> = {
  SENT: 'success',
  SKIPPED: 'warning',
  FAILED: 'error',
};

/** Sends a test email with the current SMTP settings and reports the outcome. */
export function SendTestEmailDialog({ open, onClose }: SendTestEmailDialogProps) {
  const sendTest = useSendTestEmail();
  const [recipient, setRecipient] = useState('');
  const [result, setResult] = useState<SendTestEmailResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setResult(null);
    try {
      setResult(await sendTest.mutateAsync(recipient.trim()));
    } catch (err) {
      setError(apiErrorMessage(err, 'The test email could not be sent.'));
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <Box component="form" onSubmit={onSubmit} noValidate>
        <DialogTitle>Send test email</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Recipient"
              type="email"
              value={recipient}
              onChange={(e) => setRecipient(e.target.value)}
              fullWidth
              required
              autoComplete="off"
              helperText="Uses the configured SMTP settings."
            />
            {result && <Alert severity={SEVERITY[result.status]}>{result.detail}</Alert>}
            {error && <Alert severity="error">{error}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={onClose} color="inherit">
            Close
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={sendTest.isPending || recipient.trim().length === 0}
          >
            {sendTest.isPending ? 'Sending…' : 'Send'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}
