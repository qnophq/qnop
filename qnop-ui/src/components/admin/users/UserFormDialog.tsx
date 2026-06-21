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
import FormControlLabel from '@mui/material/FormControlLabel';
import MenuItem from '@mui/material/MenuItem';
import Radio from '@mui/material/Radio';
import RadioGroup from '@mui/material/RadioGroup';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import type { AdminUserSummary, UserRole } from '../../../api/generated';
import { PasswordField } from '../../auth/PasswordField';
import { PasswordStrengthMeter } from '../../auth/PasswordStrengthMeter';
import { useCreateUser, useUpdateUser } from '../../../api/hooks/useAdminUsers';
import { apiErrorCode, apiErrorMessage } from '../../../utils/apiError';
import { passwordStrength } from '../../../utils/passwordStrength';

interface UserFormDialogProps {
  open: boolean;
  mode: 'create' | 'edit';
  user?: AdminUserSummary;
  onClose: () => void;
}

const ROLES: { value: UserRole; label: string }[] = [
  { value: 'MEMBER', label: 'Mitglied' },
  { value: 'AUDITOR', label: 'Auditor' },
  { value: 'ADMIN', label: 'Admin' },
];

const CONFLICT_DE: Record<string, string> = {
  EMAIL_TAKEN: 'Diese E-Mail-Adresse ist bereits vergeben.',
  USERNAME_TAKEN: 'Dieser Benutzername ist bereits vergeben.',
  SELF_LOCKOUT: 'Du kannst dein eigenes Konto nicht deaktivieren oder herabstufen.',
  LAST_ADMIN: 'Es muss mindestens ein aktiver Admin verbleiben.',
};

/**
 * Create/invite a user (mode "create") or edit name, role and status (mode "edit").
 *
 * <p>State is seeded once from props via {@code useState} initializers; the parent gives the dialog
 * a changing {@code key} on every open so it remounts fresh — no reset-via-effect (and no cascading
 * renders), which is the React-recommended way to reset form state on prop change.
 */
export function UserFormDialog({ open, mode, user, onClose }: UserFormDialogProps) {
  const createUser = useCreateUser();
  const updateUser = useUpdateUser();

  const editing = mode === 'edit' && user;
  const [displayName, setDisplayName] = useState(editing ? user.displayName : '');
  const [username, setUsername] = useState(editing ? (user.username ?? '') : '');
  const [email, setEmail] = useState(editing ? user.email : '');
  const [role, setRole] = useState<UserRole>(editing ? user.role : 'MEMBER');
  const [enabled, setEnabled] = useState(editing ? user.enabled : true);
  const [method, setMethod] = useState<'invite' | 'password'>('invite');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  const submitting = createUser.isPending || updateUser.isPending;
  const passwordOk = method === 'invite' || passwordStrength(password).acceptable;
  const canSubmit =
    mode === 'edit'
      ? displayName.trim().length > 0
      : displayName.trim().length > 0 &&
        username.trim().length >= 3 &&
        email.trim().length > 0 &&
        passwordOk;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      if (mode === 'edit' && user) {
        await updateUser.mutateAsync({ id: user.id, request: { displayName, role, enabled } });
      } else {
        await createUser.mutateAsync({
          displayName,
          username,
          email,
          role,
          initialPassword: method === 'password' ? password : undefined,
        });
      }
      onClose();
    } catch (err) {
      const code = apiErrorCode(err);
      setError(
        (code && CONFLICT_DE[code]) ??
          apiErrorMessage(err, 'Speichern fehlgeschlagen. Bitte erneut versuchen.'),
      );
    }
  };

  const isEdit = mode === 'edit';

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <Box component="form" onSubmit={onSubmit} noValidate>
        <DialogTitle>{isEdit ? 'Benutzer bearbeiten' : 'Benutzer anlegen'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2.5} sx={{ mt: 1 }}>
            <TextField
              label="Vollständiger Name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              autoComplete="name"
              fullWidth
              required
            />

            {!isEdit && (
              <>
                <TextField
                  label="Benutzername"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoComplete="off"
                  fullWidth
                  required
                  helperText="Mindestens 3 Zeichen, eindeutig."
                />
                <TextField
                  label="E-Mail"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  autoComplete="off"
                  fullWidth
                  required
                />
              </>
            )}

            <TextField
              label="Rolle"
              select
              value={role}
              onChange={(e) => setRole(e.target.value as UserRole)}
              fullWidth
            >
              {ROLES.map((r) => (
                <MenuItem key={r.value} value={r.value}>
                  {r.label}
                </MenuItem>
              ))}
            </TextField>

            {isEdit && (
              <FormControlLabel
                control={
                  <Switch checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                }
                label={enabled ? 'Konto aktiv' : 'Konto deaktiviert'}
              />
            )}

            {!isEdit && (
              <Box>
                <RadioGroup
                  value={method}
                  onChange={(e) => setMethod(e.target.value as 'invite' | 'password')}
                >
                  <FormControlLabel
                    value="invite"
                    control={<Radio size="small" />}
                    label="Einladung per E-Mail senden (Benutzer setzt eigenes Passwort)"
                  />
                  <FormControlLabel
                    value="password"
                    control={<Radio size="small" />}
                    label="Passwort jetzt setzen (Änderung beim ersten Login erforderlich)"
                  />
                </RadioGroup>
                {method === 'password' && (
                  <Box sx={{ mt: 1 }}>
                    <PasswordField
                      label="Initialpasswort"
                      value={password}
                      onChange={setPassword}
                      autoComplete="new-password"
                      required
                    />
                    <PasswordStrengthMeter password={password} />
                  </Box>
                )}
              </Box>
            )}

            {error && <Alert severity="error">{error}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={onClose} color="inherit">
            Abbrechen
          </Button>
          <Button type="submit" variant="contained" disabled={submitting || !canSubmit}>
            {isEdit ? 'Speichern' : 'Anlegen'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}
