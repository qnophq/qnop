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
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import InputAdornment from '@mui/material/InputAdornment';
import IconButton from '@mui/material/IconButton';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import { Check, Copy } from 'lucide-react';
import { useTranslation } from 'react-i18next';

interface GeneratedPasswordDialogProps {
  open: boolean;
  displayName: string;
  password: string;
  onClose: () => void;
}

/**
 * Reveals an admin-generated temporary password (issue #116). The password is
 * shown exactly once and cannot be retrieved later, so the dialog makes that
 * explicit and offers copy-to-clipboard. Sessions are already revoked and the
 * user must change the password on their next sign-in.
 */
export function GeneratedPasswordDialog({
  open,
  displayName,
  password,
  onClose,
}: GeneratedPasswordDialogProps) {
  const { t } = useTranslation(['admin', 'common']);
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(password);
      setCopied(true);
    } catch {
      // Clipboard unavailable (e.g. insecure context) — the field stays selectable.
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('users.generatedPassword.title', { name: displayName })}</DialogTitle>
      <DialogContent>
        <DialogContentText sx={{ mb: 2 }}>{t('users.generatedPassword.body')}</DialogContentText>
        <TextField
          value={password}
          fullWidth
          size="small"
          sx={{ '& input': { fontFamily: 'monospace', fontSize: 16, letterSpacing: '0.04em' } }}
          slotProps={{
            input: {
              readOnly: true,
              endAdornment: (
                <InputAdornment position="end">
                  <Tooltip title={copied ? t('common:actions.copied') : t('common:actions.copy')}>
                    <IconButton
                      aria-label={t('users.generatedPassword.copyLabel')}
                      size="small"
                      edge="end"
                      onClick={copy}
                    >
                      {copied ? <Check size={16} /> : <Copy size={16} />}
                    </IconButton>
                  </Tooltip>
                </InputAdornment>
              ),
            },
          }}
        />
        <Alert severity="warning" sx={{ mt: 2 }}>
          {t('users.generatedPassword.warning')}
        </Alert>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onClose} variant="contained">
          {t('common:actions.done')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
