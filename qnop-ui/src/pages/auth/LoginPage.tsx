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
import { AtSign } from 'lucide-react';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { AuthModeSwitch } from '../../components/auth/AuthModeSwitch';
import { OidcButtons } from '../../components/auth/OidcButtons';
import { PasswordField } from '../../components/auth/PasswordField';
import { useConfig } from '../../api/hooks/useConfig';
import { useAuthStore } from '../../stores/authStore';
import { apiErrorMessage } from '../../utils/apiError';
import { safeRedirectPath } from '../../utils/safeRedirectPath';

/**
 * OIDC redirect-failure codes set by the backend success handler (`/login?error=…`) mapped to
 * user-facing copy. Unknown codes fall back to the generic message.
 */
const OIDC_LOGIN_ERRORS: Record<string, string> = {
  account_disabled: 'Your account is disabled. Please contact an administrator.',
  email_missing:
    'The identity provider did not share an email address, which qnop requires for an account.',
  oidc: 'Sign-in via the identity provider failed. Please try again or contact an administrator.',
};

/** Login screen: local credentials, OIDC providers, links to register / reset. */
export function LoginPage() {
  const login = useAuthStore((s) => s.login);
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { data: config } = useConfig();
  const [usernameOrEmail, setUsernameOrEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(() => {
    const code = params.get('error');
    return code ? (OIDC_LOGIN_ERRORS[code] ?? OIDC_LOGIN_ERRORS.oidc) : null;
  });
  const [submitting, setSubmitting] = useState(false);

  const canRegister = !!config?.auth?.selfRegistrationEnabled;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(usernameOrEmail, password);
      if (useAuthStore.getState().passwordChangeRequired) {
        navigate('/change-password', { replace: true });
        return;
      }
      navigate(safeRedirectPath(params.get('from')), { replace: true });
    } catch (err) {
      setError(apiErrorMessage(err, 'Sign-in failed. Please check your credentials.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Welcome back"
      subtitle="Sign in to continue with your reviews."
      headerSlot={canRegister ? <AuthModeSwitch active="login" /> : undefined}
    >
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <Box component="form" onSubmit={onSubmit} noValidate>
        <Stack spacing={2}>
          <TextField
            label="Email or username"
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
              label="Password"
              value={password}
              onChange={setPassword}
              autoComplete="current-password"
              required
            />
            <Box sx={{ textAlign: 'right', mt: 0.75 }}>
              <Link
                component={RouterLink}
                to="/forgot-password"
                underline="hover"
                sx={{ fontSize: 13 }}
              >
                Forgot password?
              </Link>
            </Box>
          </Box>

          <Button type="submit" variant="contained" size="large" disabled={submitting} fullWidth>
            Sign in
          </Button>
        </Stack>
      </Box>

      <OidcButtons />
    </AuthLayout>
  );
}
