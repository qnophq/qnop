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
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { PasswordField } from '../../components/auth/PasswordField';
import { PasswordStrengthMeter } from '../../components/auth/PasswordStrengthMeter';
import { resetPassword } from '../../api/auth';
import { apiErrorMessage } from '../../utils/apiError';
import { passwordStrength } from '../../utils/passwordStrength';

/** Completes a password reset using the token from the emailed link. */
export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const mismatch = confirm.length > 0 && confirm !== password;
  const canSubmit = !!token && passwordStrength(password).acceptable && password === confirm;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await resetPassword(token, password);
      setDone(true);
    } catch (err) {
      setError(apiErrorMessage(err, 'Reset failed. Please request a new link.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout title="Choose a new password">
      {done ? (
        <>
          <Alert severity="success" sx={{ mb: 2 }}>
            Your password has been changed. You can sign in now.
          </Alert>
          <Link component={RouterLink} to="/login" underline="hover">
            To sign in
          </Link>
        </>
      ) : !token ? (
        <>
          <Alert severity="error" sx={{ mb: 2 }}>
            This link is invalid or incomplete. Please request a new one.
          </Alert>
          <Link component={RouterLink} to="/forgot-password" underline="hover">
            Request a new link
          </Link>
        </>
      ) : (
        <Box component="form" onSubmit={onSubmit} noValidate>
          <Stack spacing={2}>
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
              label="Confirm password"
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
              Save password
            </Button>
          </Stack>
        </Box>
      )}
    </AuthLayout>
  );
}
