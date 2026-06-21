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
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Link as RouterLink, Navigate } from 'react-router-dom';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { PasswordField } from '../../components/auth/PasswordField';
import { PasswordStrengthMeter } from '../../components/auth/PasswordStrengthMeter';
import { changePassword } from '../../api/auth';
import { useAuthStore } from '../../stores/authStore';
import { apiErrorMessage } from '../../utils/apiError';
import { passwordStrength } from '../../utils/passwordStrength';

/**
 * Change-password screen — used both for a forced first-login change and for a
 * voluntary change from the user menu. A successful change invalidates the
 * current session, so the user is signed out and asked to log in again.
 */
export function ChangePasswordPage() {
  const accessToken = useAuthStore((s) => s.accessToken);
  const forced = useAuthStore((s) => s.passwordChangeRequired);
  const clearAuth = useAuthStore((s) => s.clearAuth);
  const [current, setCurrent] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  // Reachable only with a session token (full auth or a forced-change session).
  if (!accessToken && !done) {
    return <Navigate to="/login" replace />;
  }

  const mismatch = confirm.length > 0 && confirm !== password;
  const canSubmit =
    current.length > 0 && passwordStrength(password).acceptable && password === confirm;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await changePassword(current, password);
      clearAuth();
      setDone(true);
    } catch (err) {
      setError(apiErrorMessage(err, 'The current password is wrong or the request failed.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Change password"
      subtitle={forced ? 'Please set a new password to continue.' : undefined}
    >
      {done ? (
        <>
          <Alert severity="success" sx={{ mb: 2 }}>
            Your password has been changed. Please sign in with the new password.
          </Alert>
          <Link component={RouterLink} to="/login" underline="hover">
            To sign in
          </Link>
        </>
      ) : (
        <Box component="form" onSubmit={onSubmit} noValidate>
          <Stack spacing={2}>
            <PasswordField
              label="Current password"
              value={current}
              onChange={setCurrent}
              autoComplete="current-password"
              required
            />
            <Box>
              <PasswordField
                label="New password"
                value={password}
                onChange={setPassword}
                autoComplete="new-password"
                required
              />
              <PasswordStrengthMeter password={password} />
            </Box>
            <PasswordField
              label="Confirm new password"
              value={confirm}
              onChange={setConfirm}
              autoComplete="new-password"
              required
            />
            {mismatch && <Alert severity="warning">The passwords don&apos;t match.</Alert>}
            {error && <Alert severity="error">{error}</Alert>}
            <Button
              type="submit"
              variant="contained"
              size="large"
              disabled={submitting || !canSubmit}
              fullWidth
            >
              Change password
            </Button>
            {!forced && (
              <Typography sx={{ fontSize: 13 }}>
                <Link component={RouterLink} to="/" underline="hover">
                  Cancel
                </Link>
              </Typography>
            )}
          </Stack>
        </Box>
      )}
    </AuthLayout>
  );
}
