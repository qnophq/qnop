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
import CircularProgress from '@mui/material/CircularProgress';
import InputAdornment from '@mui/material/InputAdornment';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import { ArrowRight, AtSign } from 'lucide-react';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { AuthModeSwitch } from '../../components/auth/AuthModeSwitch';
import { OidcButtons } from '../../components/auth/OidcButtons';
import { PasswordField } from '../../components/auth/PasswordField';
import { useConfig } from '../../api/hooks/useConfig';
import { useAuthStore } from '../../stores/authStore';
import { apiErrorMessage } from '../../utils/apiError';
import { safeRedirectPath } from '../../utils/safeRedirectPath';
import { useTranslation } from 'react-i18next';

/**
 * OIDC redirect-failure codes set by the backend success handler (`/login?error=…`) that have
 * their own copy (`login.oidcErrors.*`). Unknown codes fall back to the generic `oidc` message.
 */
const OIDC_LOGIN_ERROR_CODES = ['account_disabled', 'email_missing', 'oidc'] as const;
type OidcLoginErrorCode = (typeof OIDC_LOGIN_ERROR_CODES)[number];

function oidcErrorCode(code: string | null): OidcLoginErrorCode | null {
  if (!code) return null;
  return (OIDC_LOGIN_ERROR_CODES as readonly string[]).includes(code)
    ? (code as OidcLoginErrorCode)
    : 'oidc';
}

/** Login screen: local credentials, OIDC providers, links to register / reset. */
export function LoginPage() {
  const { t } = useTranslation('auth');
  const login = useAuthStore((s) => s.login);
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { data: config } = useConfig();
  const [usernameOrEmail, setUsernameOrEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  // Kept as a code and translated at render, so the message follows a language switch.
  const [oidcError, setOidcError] = useState(() => oidcErrorCode(params.get('error')));
  const shownError = error ?? (oidcError ? t(`login.oidcErrors.${oidcError}`) : null);
  const [submitting, setSubmitting] = useState(false);

  const canRegister = !!config?.auth?.selfRegistrationEnabled;
  // Issue #713: a deployment may run without self-service reset. The link used to
  // lead to a form whose submit was silently swallowed, so the sender waited for a
  // mail nobody was going to send.
  const canResetPassword = !!config?.auth?.passwordResetEnabled;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setOidcError(null);
    setSubmitting(true);
    try {
      await login(usernameOrEmail, password);
      if (useAuthStore.getState().passwordChangeRequired) {
        navigate('/change-password', { replace: true });
        return;
      }
      navigate(safeRedirectPath(params.get('from')), { replace: true });
    } catch (err) {
      setError(apiErrorMessage(err, t('login.failed')));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title={t('login.title')}
      subtitle={t('login.subtitle')}
      headerSlot={canRegister ? <AuthModeSwitch active="login" /> : undefined}
    >
      {shownError && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          onClose={() => {
            setError(null);
            setOidcError(null);
          }}
        >
          {shownError}
        </Alert>
      )}
      <Box component="form" onSubmit={onSubmit} noValidate>
        <Stack spacing={2}>
          <TextField
            label={t('login.usernameOrEmail')}
            value={usernameOrEmail}
            onChange={(e) => setUsernameOrEmail(e.target.value)}
            autoComplete="username"
            fullWidth
            required
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <AtSign size={17} />
                  </InputAdornment>
                ),
              },
            }}
          />
          <Box>
            <PasswordField
              label={t('shared.password')}
              value={password}
              onChange={setPassword}
              autoComplete="current-password"
              required
            />
            {canResetPassword && (
              <Box sx={{ textAlign: 'right', mt: 0.75 }}>
                <Link
                  component={RouterLink}
                  to="/forgot-password"
                  underline="hover"
                  sx={{ fontSize: 13 }}
                >
                  {t('login.forgotPassword')}
                </Link>
              </Box>
            )}
          </Box>

          <Button
            type="submit"
            variant="contained"
            size="large"
            disabled={submitting}
            fullWidth
            endIcon={
              submitting ? <CircularProgress size={16} color="inherit" /> : <ArrowRight size={16} />
            }
          >
            {t('login.submit')}
          </Button>
        </Stack>
      </Box>

      <OidcButtons />
    </AuthLayout>
  );
}
