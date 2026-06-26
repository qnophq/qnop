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
import { apiErrorCode, apiErrorMessage, apiFieldErrors } from '../../../utils/apiError';
import { isEmail } from '../../../utils/validation';
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
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});

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
  const isEdit = mode === 'edit';

  // Per-field client errors; shown once a save is attempted (the OIDC dialog pattern).
  const clientErrors: Record<string, string> = {};
  if (displayName.trim().length === 0) {
    clientErrors.displayName = 'A full name is required.';
  }
  if (!isEdit) {
    if (username.trim().length < 3) {
      clientErrors.username = 'Enter at least 3 characters.';
    }
    if (email.trim().length === 0) {
      clientErrors.email = 'An email address is required.';
    } else if (!isEmail(email)) {
      clientErrors.email = 'Enter a valid email address.';
    }
    if (method === 'password' && !passwordStrength(password).acceptable) {
      clientErrors.password = 'Choose a stronger password (at least 8 characters).';
    }
  }

  const fieldError = (field: string): string | undefined =>
    serverErrors[field] ?? (submitAttempted ? clientErrors[field] : undefined);

  const clearServer = (field: string) =>
    setServerErrors((prev) => {
      if (!(field in prev)) return prev;
      const rest = { ...prev };
      delete rest[field];
      return rest;
    });

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    if (Object.keys(clientErrors).length > 0) {
      setSubmitAttempted(true);
      return;
    }
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
      if (code === 'EMAIL_TAKEN') {
        setServerErrors({ email: CONFLICT_MESSAGES.EMAIL_TAKEN });
      } else if (code === 'USERNAME_TAKEN') {
        setServerErrors({ username: CONFLICT_MESSAGES.USERNAME_TAKEN });
      } else {
        const serverFieldErrors = apiFieldErrors(err);
        if (Object.keys(serverFieldErrors).length > 0) {
          setServerErrors(serverFieldErrors);
        } else {
          setError(
            (code && CONFLICT_MESSAGES[code]) ??
              apiErrorMessage(err, 'Saving failed. Please try again.'),
          );
        }
      }
    }
  };

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
              onChange={(e) => {
                setDisplayName(e.target.value);
                clearServer('displayName');
              }}
              autoComplete="name"
              fullWidth
              required
              error={Boolean(fieldError('displayName'))}
              helperText={fieldError('displayName')}
            />

            {!isEdit && (
              <>
                <TextField
                  label="Username"
                  value={username}
                  onChange={(e) => {
                    setUsername(e.target.value);
                    clearServer('username');
                  }}
                  autoComplete="off"
                  fullWidth
                  required
                  error={Boolean(fieldError('username'))}
                  helperText={fieldError('username') ?? 'At least 3 characters, unique.'}
                />
                <TextField
                  label="Email"
                  type="email"
                  value={email}
                  onChange={(e) => {
                    setEmail(e.target.value);
                    clearServer('email');
                  }}
                  autoComplete="off"
                  fullWidth
                  required
                  error={Boolean(fieldError('email'))}
                  helperText={fieldError('email')}
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
                      onChange={(next) => {
                        setPassword(next);
                        clearServer('password');
                      }}
                      autoComplete="new-password"
                      required
                      error={Boolean(fieldError('password'))}
                      helperText={fieldError('password')}
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
          <Button type="submit" variant="contained" disabled={submitting}>
            {isEdit ? 'Save' : 'Create'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}
