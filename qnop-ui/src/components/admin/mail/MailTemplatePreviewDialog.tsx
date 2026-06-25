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
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { MailTemplatePreviewResponse } from '../../../api/generated';

interface MailTemplatePreviewDialogProps {
  open: boolean;
  loading: boolean;
  preview: MailTemplatePreviewResponse | null;
  onClose: () => void;
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Box>
      <Typography sx={{ fontSize: 12, fontWeight: 600, color: 'text.secondary', mb: 0.5 }}>
        {label}
      </Typography>
      {children}
    </Box>
  );
}

/**
 * Shows a rendered template (subject + plain-text, and the HTML body in a
 * sandboxed iframe so untrusted markup cannot run scripts or navigate). The
 * server renders with representative sample data.
 */
export function MailTemplatePreviewDialog({
  open,
  loading,
  preview,
  onClose,
}: MailTemplatePreviewDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Preview (sample data)</DialogTitle>
      <DialogContent dividers>
        {loading && (
          <Typography color="text.secondary" sx={{ fontSize: 14 }}>
            Rendering…
          </Typography>
        )}
        {!loading && preview && (
          <Stack spacing={2.5}>
            <Field label="SUBJECT">
              <Typography sx={{ fontWeight: 600 }}>{preview.subject}</Typography>
            </Field>
            <Field label="PLAIN TEXT">
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 1.5,
                  bgcolor: 'action.hover',
                  borderRadius: 1,
                  fontSize: 13,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  fontFamily: 'monospace',
                }}
              >
                {preview.bodyPlain}
              </Box>
            </Field>
            {preview.bodyHtml && (
              <Field label="HTML">
                <Box
                  component="iframe"
                  title="HTML preview"
                  srcDoc={preview.bodyHtml}
                  sandbox=""
                  sx={{
                    width: '100%',
                    height: 360,
                    border: 1,
                    borderColor: 'divider',
                    borderRadius: 1,
                    bgcolor: 'background.paper',
                  }}
                />
              </Field>
            )}
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onClose} variant="contained">
          Close
        </Button>
      </DialogActions>
    </Dialog>
  );
}
