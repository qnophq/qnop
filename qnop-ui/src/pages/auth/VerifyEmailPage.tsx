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
import { verifyEmail } from '../../api/auth';
import { apiErrorCode, apiErrorMessage } from '../../utils/apiError';
import { useTranslation } from 'react-i18next';

type Status = 'form' | 'success' | 'invalid';

/**
 * Landing page for the email-verification link (issue #900). Verification asks for the
 * password chosen at registration: only the owner of the mailbox gets the link, and only
 * a password they know becomes the account's credential. A mistyped password keeps the
 * link usable; a dead link (or an account an administrator has decided about) ends here.
 */
export function VerifyEmailPage() {
  const { t } = useTranslation('auth');
  const canRegister = !!useConfig().data?.auth?.selfRegistrationEnabled;
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [status, setStatus] = useState<Status>(token ? 'form' : 'invalid');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await verifyEmail(token, password);
      setStatus('success');
    } catch (err) {
      const code = apiErrorCode(err);
      if (code === 'VERIFICATION_PASSWORD_MISMATCH') {
        setError(t('verifyEmail.passwordMismatch'));
      } else if (code === 'INVALID_VERIFICATION_TOKEN') {
        setStatus('invalid');
      } else {
        setError(apiErrorMessage(err, t('verifyEmail.failed')));
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout title={t('verifyEmail.title')} subtitle={t('verifyEmail.subtitle')}>
      {status === 'form' && (
        <Box component="form" onSubmit={onSubmit} noValidate>
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
