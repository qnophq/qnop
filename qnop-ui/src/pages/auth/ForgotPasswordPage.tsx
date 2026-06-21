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
import { Link as RouterLink } from 'react-router-dom';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { forgotPassword } from '../../api/auth';
import { apiErrorMessage } from '../../utils/apiError';

/** Requests a password-reset email. The response is a uniform acknowledgement. */
export function ForgotPasswordPage() {
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
      setError(apiErrorMessage(err, 'Anfrage fehlgeschlagen. Bitte später erneut versuchen.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Passwort zurücksetzen"
      subtitle="Wir senden dir einen Link zum Zurücksetzen deines Passworts."
    >
      {done ? (
        <>
          <Alert severity="success" sx={{ mb: 2 }}>
            Wenn ein Konto mit dieser Adresse existiert, ist eine E-Mail mit einem Reset-Link
            unterwegs.
          </Alert>
          <Link component={RouterLink} to="/login" underline="hover">
            Zurück zur Anmeldung
          </Link>
        </>
      ) : (
        <>
          <Box component="form" onSubmit={onSubmit} noValidate>
            <Stack spacing={2}>
              <TextField
                label="Arbeits-E-Mail"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                fullWidth
                required
              />
              {error && <Alert severity="error">{error}</Alert>}
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={submitting}
                fullWidth
              >
                Link senden
              </Button>
            </Stack>
          </Box>
          <Box sx={{ mt: 3 }}>
            <Link component={RouterLink} to="/login" underline="hover" sx={{ fontSize: 13 }}>
              Zurück zur Anmeldung
            </Link>
          </Box>
        </>
      )}
    </AuthLayout>
  );
}
