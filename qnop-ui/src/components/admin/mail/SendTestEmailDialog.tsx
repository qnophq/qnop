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
  /** Prefills the recipient (e.g. the signed-in admin's own address). */
  initialRecipient?: string;
  /**
   * Custom sender — the template editor posts the rendered draft (issue #316).
   * Defaults to the generic SMTP test message.
   */
  send?: (recipient: string) => Promise<SendTestEmailResponse>;
  /** Replaces the default helper line under the recipient field. */
  helperText?: string;
}

const SEVERITY: Record<SendTestEmailResponse['status'], 'success' | 'warning' | 'error'> = {
  SENT: 'success',
  SKIPPED: 'warning',
  FAILED: 'error',
};

/** Sends a test email with the current SMTP settings and reports the outcome. */
export function SendTestEmailDialog({
  open,
  onClose,
  initialRecipient = '',
  send,
  helperText = 'Uses the configured SMTP settings.',
}: SendTestEmailDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      {/* The form lives in its own component: the Dialog unmounts its children
          when closed, so every opening starts fresh (prefilled recipient, no
          stale result) without effect-driven state resets. */}
      <SendTestEmailForm
        onClose={onClose}
        initialRecipient={initialRecipient}
        send={send}
        helperText={helperText}
      />
    </Dialog>
  );
}

function SendTestEmailForm({
  onClose,
  initialRecipient,
  send,
  helperText,
}: Omit<SendTestEmailDialogProps, 'open'> & { initialRecipient: string; helperText: string }) {
  const sendTest = useSendTestEmail();
  const [recipient, setRecipient] = useState(initialRecipient);
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<SendTestEmailResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    // The dialog renders in a portal, but React bubbles the submit through the
    // REACT tree — without this, a host page's surrounding form submits too
    // (the template editor would silently save the draft).
    event.stopPropagation();
    setError(null);
    setResult(null);
    setSending(true);
    try {
      const to = recipient.trim();
      setResult(await (send ? send(to) : sendTest.mutateAsync(to)));
    } catch (err) {
      setError(apiErrorMessage(err, 'The test email could not be sent.'));
    } finally {
      setSending(false);
    }
  };

  return (
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
            helperText={helperText}
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
          disabled={sending || recipient.trim().length === 0}
        >
          {sending ? 'Sending…' : 'Send'}
        </Button>
      </DialogActions>
    </Box>
  );
}
