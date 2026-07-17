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
      setError(apiErrorMessage(err, 'Request failed. Please try again later.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Locked out? It happens."
      subtitle="Enter your email and we'll send you a secure link to get back to your reviews."
    >
      {done ? (
        <>
          <Alert severity="success" sx={{ mb: 2 }}>
            If an account exists for this address, a reset link is on its way — check your inbox.
          </Alert>
          <Link component={RouterLink} to="/login" underline="hover">
            Back to sign in
          </Link>
        </>
      ) : (
        <>
          <Box component="form" onSubmit={onSubmit} noValidate>
            <Stack spacing={2}>
              <TextField
                label="Work email"
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
                Send link
              </Button>
            </Stack>
          </Box>
          <Box sx={{ mt: 3 }}>
            <Link component={RouterLink} to="/login" underline="hover" sx={{ fontSize: 13 }}>
              Back to sign in
            </Link>
          </Box>
        </>
      )}
    </AuthLayout>
  );
}
