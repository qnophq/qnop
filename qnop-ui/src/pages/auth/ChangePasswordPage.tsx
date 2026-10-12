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
import IconButton from '@mui/material/IconButton';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { Link as RouterLink, Navigate } from 'react-router';
import { Check, Copy, Sparkles } from 'lucide-react';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { PasswordField } from '../../components/auth/PasswordField';
import { PasswordStrengthMeter } from '../../components/auth/PasswordStrengthMeter';
import { changePassword } from '../../api/auth';
import { useAuthStore } from '../../stores/authStore';
import { apiErrorMessage } from '../../utils/apiError';
import { generateStrongPassword, passwordStrength } from '../../utils/passwordStrength';
import { useTranslation } from 'react-i18next';

/**
 * Change-password screen — used both for a forced first-login change and for a
 * voluntary change from the user menu. A successful change invalidates the
 * current session, so the user is signed out and asked to log in again.
 */
export function ChangePasswordPage() {
  const { t } = useTranslation('auth');
  const accessToken = useAuthStore((s) => s.accessToken);
  const forced = useAuthStore((s) => s.passwordChangeRequired);
  const source = useAuthStore((s) => s.source);
  const clearAuth = useAuthStore((s) => s.clearAuth);
  const [current, setCurrent] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [generated, setGenerated] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  // Reachable only with a session token (full auth or a forced-change session).
  if (!accessToken && !done) {
    return <Navigate to="/login" replace />;
  }

  // External (OIDC) accounts have no local password to change; bounce a direct
  // hit to the home page. A forced-change session keeps source null (its
  // /users/me is 403-gated), so this never blocks the local first-login flow.
  if (source === 'EXTERNAL' && !done) {
    return <Navigate to="/" replace />;
  }

  const mismatch = confirm.length > 0 && confirm !== password;
  const canSubmit =
    current.length > 0 && passwordStrength(password).acceptable && password === confirm;

  const onGenerate = () => {
    const value = generateStrongPassword();
    setPassword(value);
    setConfirm(value);
    setGenerated(value);
    setCopied(false);
  };

  const copyGenerated = async () => {
    if (!generated) return;
    try {
      await navigator.clipboard.writeText(generated);
      setCopied(true);
    } catch {
      // Clipboard unavailable (e.g. insecure context) — the value stays visible to copy by hand.
    }
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await changePassword(current, password);
      clearAuth();
      setDone(true);
    } catch (err) {
      setError(apiErrorMessage(err, t('changePassword.failed')));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title={t('changePassword.title')}
      subtitle={forced ? t('changePassword.forcedSubtitle') : undefined}
    >
      {done ? (
        <>
          <Alert severity="success" sx={{ mb: 2 }}>
            {t('changePassword.done')}
          </Alert>
          <Link component={RouterLink} to="/login" underline="hover">
            {t('shared.toSignIn')}
          </Link>
        </>
      ) : (
        <Box component="form" onSubmit={onSubmit} noValidate>
          <Stack spacing={2}>
            <PasswordField
              label={t('changePassword.currentPassword')}
              value={current}
              onChange={setCurrent}
              autoComplete="current-password"
              required
            />
            <Box>
              <PasswordField
                label={t('shared.newPassword')}
                value={password}
                onChange={(value) => {
                  setPassword(value);
                  setGenerated(null);
                }}
                autoComplete="new-password"
                required
              />
              <PasswordStrengthMeter password={password} />
              <Button
                type="button"
                size="small"
                startIcon={<Sparkles size={15} />}
                onClick={onGenerate}
                sx={{ mt: 0.5, textTransform: 'none' }}
              >
                {t('changePassword.generate')}
              </Button>
              {generated && (
                <Stack
                  direction="row"
                  spacing={1}
                  sx={{
                    mt: 1,
                    p: 1,
                    pl: 1.5,
                    borderRadius: 1,
                    bgcolor: 'action.hover',
                    alignItems: 'center',
                  }}
                >
                  <Typography
                    sx={{ fontFamily: 'monospace', fontSize: 14, flex: 1, wordBreak: 'break-all' }}
                  >
                    {generated}
                  </Typography>
                  <Tooltip title={copied ? t('changePassword.copied') : t('changePassword.copy')}>
                    <IconButton
                      size="small"
                      aria-label={t('changePassword.copyAria')}
                      onClick={copyGenerated}
                    >
                      {copied ? <Check size={15} /> : <Copy size={15} />}
                    </IconButton>
                  </Tooltip>
                </Stack>
              )}
              {generated && (
                <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
                  {t('changePassword.generatedHint')}
                </Typography>
              )}
            </Box>
            <PasswordField
              label={t('changePassword.confirmPassword')}
              value={confirm}
              onChange={(value) => {
                setConfirm(value);
                setGenerated(null);
              }}
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
              {t('changePassword.submit')}
            </Button>
            {!forced && (
              <Typography sx={{ fontSize: 13 }}>
                <Link component={RouterLink} to="/" underline="hover">
                  {t('changePassword.cancel')}
                </Link>
              </Typography>
            )}
          </Stack>
        </Box>
      )}
    </AuthLayout>
  );
}
