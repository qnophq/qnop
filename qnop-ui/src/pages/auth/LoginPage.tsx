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
import Typography from '@mui/material/Typography';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { OidcButtons } from '../../components/auth/OidcButtons';
import { PasswordField } from '../../components/auth/PasswordField';
import { useConfig } from '../../api/hooks/useConfig';
import { useAuthStore } from '../../stores/authStore';
import { apiErrorMessage } from '../../utils/apiError';
import { safeRedirectPath } from '../../utils/safeRedirectPath';

/** Login screen: local credentials, OIDC providers, links to register / reset. */
export function LoginPage() {
  const login = useAuthStore((s) => s.login);
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { data: config } = useConfig();
  const [usernameOrEmail, setUsernameOrEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

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
      setError(apiErrorMessage(err, 'Anmeldung fehlgeschlagen. Bitte Zugangsdaten prüfen.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout title="Willkommen zurück" subtitle="Melde dich an, um deine Reviews fortzusetzen.">
      <Box component="form" onSubmit={onSubmit} noValidate>
        <Stack spacing={2}>
          <TextField
            label="E-Mail oder Benutzername"
            value={usernameOrEmail}
            onChange={(e) => setUsernameOrEmail(e.target.value)}
            autoComplete="username"
            fullWidth
            required
          />
          <Box>
            <PasswordField
              label="Passwort"
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
                Passwort vergessen?
              </Link>
            </Box>
          </Box>

          {error && <Alert severity="error">{error}</Alert>}

          <Button type="submit" variant="contained" size="large" disabled={submitting} fullWidth>
            Anmelden
          </Button>
        </Stack>
      </Box>

      <OidcButtons />

      {config?.auth?.selfRegistrationEnabled && (
        <Typography sx={{ mt: 3, textAlign: 'center', fontSize: 13, color: 'text.secondary' }}>
          Noch kein Konto?{' '}
          <Link component={RouterLink} to="/register" underline="hover" sx={{ fontWeight: 500 }}>
            Konto erstellen
          </Link>
        </Typography>
      )}
    </AuthLayout>
  );
}
