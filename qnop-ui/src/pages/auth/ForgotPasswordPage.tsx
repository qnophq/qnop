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
import InputAdornment from '@mui/material/InputAdornment';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import { Mail } from 'lucide-react';
import { Link as RouterLink, Navigate } from 'react-router';
import { useConfig } from '../../api/hooks/useConfig';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { forgotPassword } from '../../api/auth';
import { apiErrorMessage } from '../../utils/apiError';
import { useTranslation } from 'react-i18next';

/** Requests a password-reset email. The response is a uniform acknowledgement. */
export function ForgotPasswordPage() {
  const { t } = useTranslation('auth');
  const { data: config, isLoading: configLoading } = useConfig();
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await forgotPassword(email);
      setDone(true);
    } catch (err) {
      setError(apiErrorMessage(err, t('forgotPassword.failed')));
    } finally {
      setSubmitting(false);
    }
  };

  // Same shape as RegisterPage: a screen for a capability the deployment does not
  // offer is not a screen, and the endpoint refuses anyway (issue #713). Nothing
  // is decided while the config is still loading — "not known yet" is not "off".
  if (!configLoading && !config?.auth?.passwordResetEnabled) {
    return <Navigate to="/login" replace />;
  }

  return (
    <AuthLayout title={t('forgotPassword.title')} subtitle={t('forgotPassword.subtitle')}>
      {done ? (
        <>
          <Alert severity="success" sx={{ mb: 2 }}>
            {t('forgotPassword.done')}
          </Alert>
          <Link component={RouterLink} to="/login" underline="hover">
            {t('shared.backToSignIn')}
          </Link>
        </>
      ) : (
        <>
          <Box component="form" onSubmit={onSubmit} noValidate>
            <Stack spacing={2}>
              <TextField
                label={t('shared.workEmail')}
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                fullWidth
                required
                slotProps={{
                  input: {
                    startAdornment: (
                      <InputAdornment position="start">
                        <Mail size={17} />
                      </InputAdornment>
                    ),
                  },
                }}
              />
              {error && <Alert severity="error">{error}</Alert>}
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={submitting}
                fullWidth
              >
                {t('forgotPassword.submit')}
              </Button>
            </Stack>
          </Box>
          <Box sx={{ mt: 3 }}>
            <Link component={RouterLink} to="/login" underline="hover" sx={{ fontSize: 13 }}>
              {t('shared.backToSignIn')}
            </Link>
          </Box>
        </>
      )}
    </AuthLayout>
  );
}
