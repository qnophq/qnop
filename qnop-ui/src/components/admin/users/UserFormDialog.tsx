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
import { useRemoveUserAvatar, useUploadUserAvatar } from '../../../api/hooks/useAvatar';
import { apiErrorCode, apiErrorMessage } from '../../../utils/apiError';
import { passwordStrength } from '../../../utils/passwordStrength';
import { AvatarUploader } from '../../profile/AvatarUploader';

interface UserFormDialogProps {
  open: boolean;
  mode: 'create' | 'edit';
  user?: AdminUserSummary;
  onClose: () => void;
}

const ROLES: { value: UserRole; label: string }[] = [
  { value: 'MEMBER', label: 'Member' },
  { value: 'AUDITOR', label: 'Auditor' },
  { value: 'ADMIN', label: 'Admin' },
];

const CONFLICT_MESSAGES: Record<string, string> = {
  EMAIL_TAKEN: 'An account with this email already exists.',
  USERNAME_TAKEN: 'This username is already taken.',
  SELF_LOCKOUT: "You can't disable or change the role of your own account.",
  LAST_ADMIN: 'At least one enabled admin must remain.',
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
  const uploadAvatar = useUploadUserAvatar();
  const removeAvatar = useRemoveUserAvatar();

  const editing = mode === 'edit' && user;
  const [displayName, setDisplayName] = useState(editing ? user.displayName : '');
  const [avatarUrl, setAvatarUrl] = useState<string | null>(
    editing ? (user.avatarUrl ?? null) : null,
  );
  const [username, setUsername] = useState(editing ? (user.username ?? '') : '');
  const [email, setEmail] = useState(editing ? user.email : '');
  const [role, setRole] = useState<UserRole>(editing ? user.role : 'MEMBER');
  const [enabled, setEnabled] = useState(editing ? user.enabled : true);
  const [method, setMethod] = useState<'invite' | 'password'>('invite');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  const submitting = createUser.isPending || updateUser.isPending;
  const avatarBusy = uploadAvatar.isPending || removeAvatar.isPending;

  const onAvatarSelect = async (blob: Blob) => {
    if (!user) return;
    setError(null);
    try {
      const result = await uploadAvatar.mutateAsync({ userId: user.id, file: blob });
      setAvatarUrl(result.avatarUrl ?? null);
    } catch (err) {
      setError(apiErrorMessage(err, 'The picture could not be uploaded.'));
    }
  };

  const onAvatarRemove = async () => {
    if (!user) return;
    setError(null);
    try {
      await removeAvatar.mutateAsync(user.id);
      setAvatarUrl(null);
    } catch (err) {
      setError(apiErrorMessage(err, 'The picture could not be removed.'));
    }
  };
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
        (code && CONFLICT_MESSAGES[code]) ??
          apiErrorMessage(err, 'Saving failed. Please try again.'),
      );
    }
  };

  const isEdit = mode === 'edit';

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <Box component="form" onSubmit={onSubmit} noValidate>
        <DialogTitle>{isEdit ? 'Edit user' : 'Add user'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2.5} sx={{ mt: 1 }}>
            {isEdit && user && (
              <AvatarUploader
                name={displayName || user.displayName}
                imageUrl={avatarUrl}
                busy={avatarBusy}
                onSelect={onAvatarSelect}
                onRemove={onAvatarRemove}
              />
            )}

            <TextField
              label="Full name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              autoComplete="name"
              fullWidth
              required
            />

            {!isEdit && (
              <>
                <TextField
                  label="Username"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoComplete="off"
                  fullWidth
                  required
                  helperText="At least 3 characters, unique."
                />
                <TextField
                  label="Email"
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
              label="Role"
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
                label={enabled ? 'Account active' : 'Account disabled'}
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
                    label="Send an email invitation (the user sets their own password)"
                  />
                  <FormControlLabel
                    value="password"
                    control={<Radio size="small" />}
                    label="Set a password now (must be changed on first login)"
                  />
                </RadioGroup>
                {method === 'password' && (
                  <Box sx={{ mt: 1 }}>
                    <PasswordField
                      label="Initial password"
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
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={submitting || !canSubmit}>
            {isEdit ? 'Save' : 'Create'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}
