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

import { useState, type FormEvent, type ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import CircularProgress from '@mui/material/CircularProgress';
import FormControlLabel from '@mui/material/FormControlLabel';
import InputAdornment from '@mui/material/InputAdornment';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { ArrowRight, AtSign, Mail, User } from 'lucide-react';
import { Link as RouterLink, Navigate } from 'react-router-dom';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { AuthModeSwitch } from '../../components/auth/AuthModeSwitch';
import { OidcButtons } from '../../components/auth/OidcButtons';
import { PasswordField } from '../../components/auth/PasswordField';
import { PasswordStrengthMeter } from '../../components/auth/PasswordStrengthMeter';
import { register } from '../../api/auth';
import { useConfig } from '../../api/hooks/useConfig';
import { apiErrorMessage } from '../../utils/apiError';
import { passwordStrength } from '../../utils/passwordStrength';

const startIcon = (icon: ReactNode) => ({
  input: { startAdornment: <InputAdornment position="start">{icon}</InputAdornment> },
});

/** Registration screen, available only when self-registration is enabled. */
export function RegisterPage() {
  const { data: config, isLoading } = useConfig();
  const [displayName, setDisplayName] = useState('');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [terms, setTerms] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  if (!isLoading && !config?.auth?.selfRegistrationEnabled) {
    return <Navigate to="/login" replace />;
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register({ displayName, username, email, password });
      setDone(true);
    } catch (err) {
      setError(apiErrorMessage(err, 'Registration failed. Please try again later.'));
    } finally {
      setSubmitting(false);
    }
  };

  if (done) {
    return (
      <AuthLayout title="Almost there">
        <Alert severity="success" sx={{ mb: 2 }}>
          If the details are valid, we&apos;ve sent you a confirmation email. Please confirm your
          address to finish signing up.
        </Alert>
        <Link component={RouterLink} to="/login" underline="hover">
          To sign in
        </Link>
      </AuthLayout>
    );
  }

  const canSubmit = terms && passwordStrength(password).acceptable;

  return (
    <AuthLayout
      title="Create account"
      subtitle="Ready in two minutes."
      headerSlot={<AuthModeSwitch active="register" />}
    >
      <Box component="form" onSubmit={onSubmit} noValidate>
        <Stack spacing={2}>
          <TextField
            label="Full name"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            autoComplete="name"
            fullWidth
            required
            slotProps={startIcon(<User size={17} />)}
          />
          <TextField
            label="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            fullWidth
            required
            slotProps={startIcon(<AtSign size={17} />)}
          />
          <TextField
            label="Work email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            fullWidth
            required
            slotProps={startIcon(<Mail size={17} />)}
          />
          <Box>
            <PasswordField
              label="Password"
              value={password}
              onChange={setPassword}
              autoComplete="new-password"
              required
            />
            <PasswordStrengthMeter password={password} />
          </Box>

          <FormControlLabel
            control={
              <Checkbox checked={terms} onChange={(e) => setTerms(e.target.checked)} size="small" />
            }
            label={
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                I accept the terms of service and the privacy policy (GDPR).
              </Typography>
            }
          />

          {error && <Alert severity="error">{error}</Alert>}

          <Button
            type="submit"
            variant="contained"
            size="large"
            disabled={submitting || !canSubmit}
            fullWidth
            endIcon={
              submitting ? <CircularProgress size={16} color="inherit" /> : <ArrowRight size={16} />
            }
          >
            Create account
          </Button>
        </Stack>
      </Box>

      <OidcButtons />
    </AuthLayout>
  );
}
