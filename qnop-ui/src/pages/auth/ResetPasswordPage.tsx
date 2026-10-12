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
import { Link as RouterLink, useSearchParams } from 'react-router';
import { useConfig } from '../../api/hooks/useConfig';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { PasswordField } from '../../components/auth/PasswordField';
import { PasswordStrengthMeter } from '../../components/auth/PasswordStrengthMeter';
import { resetPassword } from '../../api/auth';
import { apiErrorMessage } from '../../utils/apiError';
import { passwordStrength } from '../../utils/passwordStrength';
import { useTranslation } from 'react-i18next';

/** Completes a password reset using the token from the emailed link. */
export function ResetPasswordPage() {
  const { t } = useTranslation('auth');
  // The reset itself keeps working from a valid link even where the self-service
  // request is off — an administrator may still hand somebody a link (#713).
  const canResetPassword = !!useConfig().data?.auth?.passwordResetEnabled;
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
      setError(apiErrorMessage(err, t('resetPassword.failed')));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout title={t('resetPassword.title')} subtitle={t('resetPassword.subtitle')}>
      {done ? (
        <>
          <Alert severity="success" sx={{ mb: 2 }}>
            {t('resetPassword.done')}
          </Alert>
          <Link component={RouterLink} to="/login" underline="hover">
            {t('shared.toSignIn')}
          </Link>
        </>
      ) : !token ? (
        <>
          <Alert severity="error" sx={{ mb: 2 }}>
            {canResetPassword
              ? t('resetPassword.invalidLink')
              : t('resetPassword.invalidLinkNoReset')}
          </Alert>
          {/* Only where there is somewhere to go: pointing at /forgot-password on a
              deployment without reset would send the reader in a circle (#713). */}
          <Link
            component={RouterLink}
            to={canResetPassword ? '/forgot-password' : '/login'}
            underline="hover"
          >
            {canResetPassword ? t('resetPassword.requestNewLink') : t('shared.toSignIn')}
          </Link>
        </>
      ) : (
        <Box component="form" onSubmit={onSubmit} noValidate>
          <Stack spacing={2}>
            <Box>
              <PasswordField
                label={t('shared.newPassword')}
                value={password}
                onChange={setPassword}
                autoComplete="new-password"
                required
              />
              <PasswordStrengthMeter password={password} />
            </Box>
            <PasswordField
              label={t('resetPassword.confirmPassword')}
              value={confirm}
              onChange={setConfirm}
              autoComplete="new-password"
              required
            />
            {mismatch && <Alert severity="warning">{t('shared.passwordsMismatch')}</Alert>}
            {error && <Alert severity="error">{error}</Alert>}
            <Button
              type="submit"
              variant="contained"
              size="large"
              disabled={submitting || !canSubmit}
              fullWidth
            >
              {t('resetPassword.submit')}
            </Button>
          </Stack>
        </Box>
      )}
    </AuthLayout>
  );
}
