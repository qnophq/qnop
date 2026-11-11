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
import TextField from '@mui/material/TextField';
import { Link as RouterLink, useSearchParams } from 'react-router';
import { useConfig } from '../../api/hooks/useConfig';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { PasswordField } from '../../components/auth/PasswordField';
import { PasswordStrengthMeter } from '../../components/auth/PasswordStrengthMeter';
import { fetchVerificationUsername, verifyEmail } from '../../api/auth';
import { apiErrorCode, apiErrorMessage } from '../../utils/apiError';
import { passwordStrength } from '../../utils/passwordStrength';
import { useTranslation } from 'react-i18next';

type Status = 'confirm' | 'setNew' | 'success' | 'invalid';

/**
 * Landing page for the email-verification link (issue #900). The link proves control of
 * the mailbox, and for an account nobody has verified yet that is control of the account:
 * the holder either confirms the registration with its password, or — when that password
 * is not theirs (someone else registered the address) or forgotten — sets their own
 * username and password. A dead link (or an account an administrator has decided about)
 * ends here.
 */
export function VerifyEmailPage() {
  const { t } = useTranslation('auth');
  const canRegister = !!useConfig().data?.auth?.selfRegistrationEnabled;
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [status, setStatus] = useState<Status>(token ? 'confirm' : 'invalid');
  const [password, setPassword] = useState('');
  const [username, setUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [mismatch, setMismatch] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  function fail(err: unknown, inSetNew: boolean) {
    const code = apiErrorCode(err);
    if (code === 'INVALID_VERIFICATION_TOKEN') {
      setStatus('invalid');
    } else if (code === 'VERIFICATION_PASSWORD_MISMATCH') {
      setMismatch(true);
      setError(t('verifyEmail.passwordMismatch'));
    } else if (code === 'VERIFICATION_USERNAME_TAKEN') {
      setError(t('verifyEmail.usernameTaken'));
      if (!inSetNew) {
        void startSetNew();
      }
    } else {
      setError(apiErrorMessage(err, t('verifyEmail.failed')));
    }
  }

  async function startSetNew() {
    try {
      setUsername(await fetchVerificationUsername(token));
      setStatus('setNew');
    } catch (err) {
      fail(err, true);
    }
  }

  const onConfirm = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await verifyEmail(token, password);
      setStatus('success');
    } catch (err) {
      fail(err, false);
    } finally {
      setSubmitting(false);
    }
  };

  const onSetNew = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await verifyEmail(token, newPassword, { setNew: true, username: username.trim() });
      setStatus('success');
    } catch (err) {
      fail(err, true);
    } finally {
      setSubmitting(false);
    }
  };

  const newMismatch = confirmPassword.length > 0 && confirmPassword !== newPassword;
  const canSetNew =
    username.trim().length >= 3 &&
    passwordStrength(newPassword).acceptable &&
    newPassword === confirmPassword;

  return (
    <AuthLayout
      title={t('verifyEmail.title')}
      subtitle={status === 'setNew' ? t('verifyEmail.setNewSubtitle') : t('verifyEmail.subtitle')}
    >
      {status === 'confirm' && (
        <Box component="form" onSubmit={onConfirm} noValidate>
          <Stack spacing={2}>
            <PasswordField
              label={t('verifyEmail.password')}
              value={password}
              onChange={setPassword}
              autoComplete="current-password"
              required
            />
            {error && <Alert severity="error">{error}</Alert>}
            <Button
              type="submit"
              variant="contained"
              size="large"
              disabled={submitting || password.length === 0}
              fullWidth
            >
              {t('verifyEmail.submit')}
            </Button>
            {mismatch && (
              <Button variant="outlined" onClick={() => void startSetNew()} fullWidth>
                {t('verifyEmail.setNewAction')}
              </Button>
            )}
          </Stack>
        </Box>
      )}
      {status === 'setNew' && (
        <Box component="form" onSubmit={onSetNew} noValidate>
          <Stack spacing={2}>
            <TextField
              label={t('verifyEmail.username')}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
              fullWidth
            />
            <Box>
              <PasswordField
                label={t('shared.newPassword')}
                value={newPassword}
                onChange={setNewPassword}
                autoComplete="new-password"
                required
              />
              <PasswordStrengthMeter password={newPassword} />
            </Box>
            <PasswordField
              label={t('verifyEmail.confirmPassword')}
              value={confirmPassword}
              onChange={setConfirmPassword}
              autoComplete="new-password"
              required
            />
            {newMismatch && <Alert severity="warning">{t('shared.passwordsMismatch')}</Alert>}
            {error && <Alert severity="error">{error}</Alert>}
            <Button
              type="submit"
              variant="contained"
              size="large"
              disabled={submitting || !canSetNew}
              fullWidth
            >
              {t('verifyEmail.setNewSubmit')}
            </Button>
          </Stack>
        </Box>
      )}
      {status === 'success' && (
        <>
          <Alert severity="success" sx={{ mb: 2 }}>
            {t('verifyEmail.success')}
          </Alert>
          <Link component={RouterLink} to="/login" underline="hover">
            {t('shared.toSignIn')}
          </Link>
        </>
      )}
      {status === 'invalid' && (
        <>
          <Alert severity="error" sx={{ mb: 2 }}>
            {t('verifyEmail.error')}
            {canRegister && (
              <Box component="span" sx={{ display: 'block', mt: 0.5 }}>
                {t('verifyEmail.deadLinkHint')}
              </Box>
            )}
          </Alert>
          <Stack direction="row" spacing={2}>
            {canRegister && (
              <Link component={RouterLink} to="/register" underline="hover">
                {t('verifyEmail.registerAgain')}
              </Link>
            )}
            <Link component={RouterLink} to="/login" underline="hover">
              {t('shared.toSignIn')}
            </Link>
          </Stack>
        </>
      )}
    </AuthLayout>
  );
}
