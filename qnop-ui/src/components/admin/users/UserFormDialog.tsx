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
import type { ParseKeys } from 'i18next';
import { useTranslation } from 'react-i18next';
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

type AdminKey = ParseKeys<'admin'>;

const ROLES: { value: UserRole; labelKey: AdminKey }[] = [
  { value: 'MEMBER', labelKey: 'roles.global.MEMBER' },
  { value: 'AUDITOR', labelKey: 'roles.global.AUDITOR' },
  { value: 'ADMIN', labelKey: 'roles.global.ADMIN' },
];

const CONFLICT_MESSAGES: Record<string, AdminKey> = {
  EMAIL_TAKEN: 'users.conflicts.EMAIL_TAKEN',
  USERNAME_TAKEN: 'users.conflicts.USERNAME_TAKEN',
  SELF_LOCKOUT: 'users.conflicts.SELF_LOCKOUT',
  LAST_ADMIN: 'users.conflicts.LAST_ADMIN',
};

/**
 * A failed action, resolved at render so it follows a language switch: a catalog message, or the
 * server's message for {@code cause} with the catalog key as its fallback.
 */
interface DialogError {
  key: AdminKey;
  cause?: unknown;
}

/** A field message: the server's own text, or a catalog key for a known conflict. */
type FieldMessage = { text: string } | { key: AdminKey };

/**
 * Create/invite a user (mode "create") or edit name, role and status (mode "edit").
 *
 * <p>State is seeded once from props via {@code useState} initializers; the parent gives the dialog
 * a changing {@code key} on every open so it remounts fresh — no reset-via-effect (and no cascading
 * renders), which is the React-recommended way to reset form state on prop change.
 */
export function UserFormDialog({ open, mode, user, onClose }: UserFormDialogProps) {
  const { t } = useTranslation(['admin', 'common']);
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
  const [error, setError] = useState<DialogError | null>(null);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [serverErrors, setServerErrors] = useState<Record<string, FieldMessage>>({});

  const submitting = createUser.isPending || updateUser.isPending;
  const avatarBusy = uploadAvatar.isPending || removeAvatar.isPending;

  const onAvatarSelect = async (blob: Blob) => {
    if (!user) return;
    setError(null);
    try {
      const result = await uploadAvatar.mutateAsync({ userId: user.id, file: blob });
      setAvatarUrl(result.avatarUrl ?? null);
    } catch (err) {
      setError({ key: 'users.form.errors.avatarUpload', cause: err });
    }
  };

  const onAvatarRemove = async () => {
    if (!user) return;
    setError(null);
    try {
      await removeAvatar.mutateAsync(user.id);
      setAvatarUrl(null);
    } catch (err) {
      setError({ key: 'users.form.errors.avatarRemove', cause: err });
    }
  };
  const isEdit = mode === 'edit';

  // Per-field client errors; shown once a save is attempted (the OIDC dialog pattern).
  const clientErrors: Record<string, string> = {};
  if (displayName.trim().length === 0) {
    clientErrors.displayName = t('users.form.errors.fullNameRequired');
  }
  if (!isEdit) {
    if (username.trim().length < 3) {
      clientErrors.username = t('users.form.errors.usernameTooShort');
    }
    if (email.trim().length === 0) {
      clientErrors.email = t('users.form.errors.emailRequired');
    } else if (!isEmail(email)) {
      clientErrors.email = t('users.form.errors.emailInvalid');
    }
    if (method === 'password' && !passwordStrength(password).acceptable) {
      clientErrors.password = t('users.form.errors.passwordWeak');
    }
  }

  const serverError = (field: string): string | undefined => {
    const message = serverErrors[field];
    if (!message) return undefined;
    return 'key' in message ? t(message.key) : message.text;
  };

  const fieldError = (field: string): string | undefined =>
    serverError(field) ?? (submitAttempted ? clientErrors[field] : undefined);

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
        setServerErrors({ email: { key: CONFLICT_MESSAGES.EMAIL_TAKEN } });
      } else if (code === 'USERNAME_TAKEN') {
        setServerErrors({ username: { key: CONFLICT_MESSAGES.USERNAME_TAKEN } });
      } else {
        const serverFieldErrors = apiFieldErrors(err);
        if (Object.keys(serverFieldErrors).length > 0) {
          setServerErrors(
            Object.fromEntries(
              Object.entries(serverFieldErrors).map(([field, text]) => [field, { text }]),
            ),
          );
        } else {
          const conflictKey = code ? CONFLICT_MESSAGES[code] : undefined;
          setError(
            conflictKey ? { key: conflictKey } : { key: 'users.form.errors.save', cause: err },
          );
        }
      }
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <Box component="form" onSubmit={onSubmit} noValidate>
        <DialogTitle>
          {isEdit ? t('users.form.editTitle') : t('users.form.createTitle')}
        </DialogTitle>
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
              label={t('users.form.fullName')}
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
                  label={t('users.form.username')}
                  value={username}
                  onChange={(e) => {
                    setUsername(e.target.value);
                    clearServer('username');
                  }}
                  autoComplete="off"
                  fullWidth
                  required
                  error={Boolean(fieldError('username'))}
                  helperText={fieldError('username') ?? t('users.form.usernameHelper')}
                />
                <TextField
                  label={t('users.form.email')}
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
              label={t('users.form.role')}
              select
              value={role}
              onChange={(e) => setRole(e.target.value as UserRole)}
              fullWidth
            >
              {ROLES.map((r) => (
                <MenuItem key={r.value} value={r.value}>
                  {t(r.labelKey)}
                </MenuItem>
              ))}
            </TextField>

            {isEdit && (
              <FormControlLabel
                control={
                  <Switch checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                }
                label={enabled ? t('users.form.accountActive') : t('users.form.accountDisabled')}
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
                    label={t('users.form.methodInvite')}
                  />
                  <FormControlLabel
                    value="password"
                    control={<Radio size="small" />}
                    label={t('users.form.methodPassword')}
                  />
                </RadioGroup>
                {method === 'password' && (
                  <Box sx={{ mt: 1 }}>
                    <PasswordField
                      label={t('users.form.initialPassword')}
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

            {error && (
              <Alert severity="error">
                {error.cause === undefined
                  ? t(error.key)
                  : apiErrorMessage(error.cause, t(error.key))}
              </Alert>
            )}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={onClose} color="inherit">
            {t('common:actions.cancel')}
          </Button>
          <Button type="submit" variant="contained" disabled={submitting}>
            {isEdit ? t('common:actions.save') : t('common:actions.create')}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}
