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

import { useMemo, useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Divider from '@mui/material/Divider';
import FormControlLabel from '@mui/material/FormControlLabel';
import LinearProgress from '@mui/material/LinearProgress';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField, { type TextFieldProps } from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { AtSign, SendHorizonal, Server, Zap } from 'lucide-react';
import type { AdminSetting, SendTestEmailResponse } from '../../api/generated';
import { useSettings, useUpdateSettings } from '../../api/hooks/useSettings';
import { useSendTestEmail } from '../../api/hooks/useMailTemplates';
import { PasswordField } from '../../components/auth/PasswordField';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { SectionCard } from '../../components/admin/layout/SectionCard';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import { ToneBadge } from '../../components/admin/ToneBadge';
import {
  ENCRYPTION_FALLBACK,
  ENCRYPTION_META,
  SMTP_FIELD,
  SMTP_GROUP_PREFIX,
  SMTP_KEYS,
  computeSmtpStatus,
} from '../../components/admin/mail/smtp/smtpConfig';
import { apiErrorMessage, apiFieldErrors } from '../../utils/apiError';
import { isEmail, isPort } from '../../utils/validation';

const TEST_SEVERITY: Record<SendTestEmailResponse['status'], 'success' | 'warning' | 'error'> = {
  SENT: 'success',
  SKIPPED: 'warning',
  FAILED: 'error',
};

/** Secrets start blank (the API masks them); everything else baselines on its value. */
function baselineOf(setting: AdminSetting): string {
  return setting.sensitive ? '' : (setting.value ?? '');
}

/**
 * Dedicated Email / SMTP administration (issue #142): outgoing-mail connection,
 * sender identity, and delivery status in three focused cards, with an inline
 * test-send. Only the changed keys are PATCHed; the encryption options come from
 * the API contract's `allowedValues`, rendered with curated labels and port hints.
 */
export function EmailServerPage() {
  const { data, isLoading, isError } = useSettings();
  const updateSettings = useUpdateSettings();
  const sendTest = useSendTestEmail();

  const smtpSettings = useMemo(
    () => (data?.settings ?? []).filter((s) => s.key.startsWith(`${SMTP_GROUP_PREFIX}.`)),
    [data],
  );
  const byKey = useMemo(() => {
    const map: Record<string, AdminSetting> = {};
    for (const setting of smtpSettings) {
      map[setting.key] = setting;
    }
    return map;
  }, [smtpSettings]);
  const baselines = useMemo(() => {
    const map: Record<string, string> = {};
    for (const setting of smtpSettings) {
      map[setting.key] = baselineOf(setting);
    }
    return map;
  }, [smtpSettings]);

  const [edits, setEdits] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const { toast, notify, clear } = useToast();
  const [testRecipient, setTestRecipient] = useState('');
  const [testResult, setTestResult] = useState<SendTestEmailResponse | null>(null);
  const [testError, setTestError] = useState<string | null>(null);

  const valueOf = (key: string): string => edits[key] ?? baselines[key] ?? '';
  const setValue = (key: string, next: string) => {
    setEdits((prev) => ({ ...prev, [key]: next }));
    // A prior test ran against the saved state; editing makes that result stale.
    setTestResult(null);
    setTestError(null);
    setServerErrors((prev) => {
      if (!(key in prev)) return prev;
      const rest = { ...prev };
      delete rest[key];
      return rest;
    });
  };

  const changed = useMemo(
    () => Object.keys(edits).filter((key) => edits[key] !== (baselines[key] ?? '')),
    [edits, baselines],
  );
  const hasChanges = changed.length > 0;

  const enabled = valueOf(SMTP_KEYS.enabled) === 'true';
  const status = computeSmtpStatus(enabled, valueOf(SMTP_KEYS.host));
  // Fall back to the curated set when the API publishes no options (null or empty).
  const allowedEncryption = byKey[SMTP_KEYS.encryption]?.allowedValues;
  const encryptionOptions =
    allowedEncryption && allowedEncryption.length > 0
      ? allowedEncryption
      : [...ENCRYPTION_FALLBACK];

  // Client-side field errors mirroring the server's SMTP constraints.
  const clientErrors = useMemo(() => {
    const valueAt = (key: string) => edits[key] ?? baselines[key] ?? '';
    const map: Record<string, string> = {};
    if (enabled && valueAt(SMTP_KEYS.host).trim() === '') {
      map[SMTP_KEYS.host] = 'A host is required to send mail.';
    }
    const port = valueAt(SMTP_KEYS.port);
    if (port.trim() !== '' && !isPort(port)) {
      map[SMTP_KEYS.port] = 'Enter a port between 1 and 65535.';
    }
    const from = valueAt(SMTP_KEYS.from);
    if (from.trim() !== '' && !isEmail(from)) {
      map[SMTP_KEYS.from] = 'Enter a valid email address.';
    }
    return map;
  }, [edits, baselines, enabled]);

  const fieldError = (key: string): string | undefined =>
    serverErrors[key] ?? (submitAttempted ? clientErrors[key] : undefined);

  const discard = () => {
    setEdits({});
    setServerErrors({});
    setSubmitAttempted(false);
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    if (Object.keys(clientErrors).length > 0) {
      setSubmitAttempted(true);
      return;
    }
    const patch = Object.fromEntries(changed.map((key) => [key, edits[key]]));
    try {
      await updateSettings.mutateAsync(patch);
      setEdits({});
      setServerErrors({});
      setSubmitAttempted(false);
      notify('SMTP settings saved.');
    } catch (err) {
      const serverFieldErrors = apiFieldErrors(err);
      if (Object.keys(serverFieldErrors).length > 0) {
        setServerErrors(serverFieldErrors);
      } else {
        setError(apiErrorMessage(err, 'The SMTP settings could not be saved.'));
      }
    }
  };

  const onSendTest = async () => {
    setTestError(null);
    setTestResult(null);
    if (!isEmail(testRecipient)) {
      setTestError('Enter a valid recipient email address.');
      return;
    }
    try {
      setTestResult(await sendTest.mutateAsync(testRecipient.trim()));
    } catch (err) {
      setTestError(apiErrorMessage(err, 'The test email could not be sent.'));
    }
  };

  /** A standard text input for a string/number SMTP field, wired to the draft. */
  const textField = (key: string, extra?: Partial<TextFieldProps>) => {
    const setting = byKey[key];
    if (!setting) return null;
    const err = fieldError(key);
    return (
      <TextField
        label={SMTP_FIELD[key]?.label ?? key}
        value={valueOf(key)}
        onChange={(e) => setValue(key, e.target.value)}
        placeholder={SMTP_FIELD[key]?.placeholder}
        error={Boolean(err)}
        helperText={err ?? setting.description}
        fullWidth
        {...extra}
      />
    );
  };

  if (isLoading) {
    return (
      <Typography color="text.secondary" sx={{ p: 1, fontSize: 14 }}>
        Loading…
      </Typography>
    );
  }
  if (isError) {
    return <Alert severity="error">The SMTP settings could not be loaded.</Alert>;
  }

  const passwordSetting = byKey[SMTP_KEYS.password];
  const passwordSet = passwordSetting?.value === '***';

  return (
    <Box component="form" onSubmit={onSubmit} noValidate>
      <Stack spacing={3}>
        <PageHeader
          title="Email / SMTP"
          description="Configure the outgoing-mail server: connection, sender identity, and delivery. Changes apply without a restart."
        />

        <SectionCard
          icon={Server}
          title="Connection"
          description="Where and how qnop reaches your SMTP server."
        >
          <Stack spacing={2.5} sx={{ maxWidth: 480 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Box sx={{ flex: 1 }}>{textField(SMTP_KEYS.host)}</Box>
              <Box sx={{ width: { sm: 150 } }}>{textField(SMTP_KEYS.port, { type: 'number' })}</Box>
            </Stack>

            {byKey[SMTP_KEYS.encryption] && (
              <TextField
                select
                label={SMTP_FIELD[SMTP_KEYS.encryption].label}
                value={valueOf(SMTP_KEYS.encryption)}
                onChange={(e) => setValue(SMTP_KEYS.encryption, e.target.value)}
                helperText={byKey[SMTP_KEYS.encryption].description}
                fullWidth
                slotProps={{
                  select: {
                    renderValue: (value) => ENCRYPTION_META[String(value)]?.label ?? String(value),
                  },
                }}
              >
                {encryptionOptions.map((option) => {
                  const meta = ENCRYPTION_META[option];
                  return (
                    <MenuItem key={option} value={option}>
                      <Box>
                        <Typography sx={{ fontSize: 14 }}>{meta?.label ?? option}</Typography>
                        {meta && (
                          <Typography color="text.secondary" sx={{ fontSize: 12 }}>
                            {meta.hint}
                          </Typography>
                        )}
                      </Box>
                    </MenuItem>
                  );
                })}
              </TextField>
            )}

            {textField(SMTP_KEYS.username, { autoComplete: 'off' })}

            {passwordSetting && (
              <PasswordField
                label={SMTP_FIELD[SMTP_KEYS.password].label}
                value={valueOf(SMTP_KEYS.password)}
                onChange={(next) => setValue(SMTP_KEYS.password, next)}
                autoComplete="new-password"
                helperText={
                  passwordSet
                    ? 'Stored encrypted. Leave blank to keep the current password.'
                    : passwordSetting.description
                }
              />
            )}
          </Stack>
        </SectionCard>

        <SectionCard
          icon={AtSign}
          title="Identity"
          description="How outgoing messages present their sender."
        >
          <Stack spacing={2.5} sx={{ maxWidth: 480 }}>
            {textField(SMTP_KEYS.from, { type: 'email', autoComplete: 'off' })}
            {textField(SMTP_KEYS.fromName)}
          </Stack>
        </SectionCard>

        <SectionCard
          icon={Zap}
          title="Status & delivery"
          description="Turn outgoing mail on and verify it with a test message."
          action={<ToneBadge tone={status.tone} label={status.label} />}
        >
          <Stack spacing={2.5} sx={{ maxWidth: 480 }}>
            {byKey[SMTP_KEYS.enabled] && (
              <FormControlLabel
                control={
                  <Switch
                    checked={enabled}
                    onChange={(e) =>
                      setValue(SMTP_KEYS.enabled, e.target.checked ? 'true' : 'false')
                    }
                  />
                }
                label={
                  <Box>
                    <Typography sx={{ fontSize: 15 }}>Enable outgoing mail</Typography>
                    <Typography color="text.secondary" sx={{ fontSize: 13 }}>
                      {status.detail}
                    </Typography>
                  </Box>
                }
                sx={{ alignItems: 'flex-start', m: 0, gap: 1 }}
              />
            )}

            <Divider />

            <Box>
              <Typography sx={{ fontSize: 15, fontWeight: 600 }}>Send a test message</Typography>
              <Typography color="text.secondary" sx={{ fontSize: 13, mt: 0.25, mb: 1.5 }}>
                Delivers to the address below using the saved SMTP settings.
              </Typography>
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1.5}
                sx={{ alignItems: { sm: 'flex-start' } }}
              >
                <TextField
                  label="Recipient"
                  type="email"
                  value={testRecipient}
                  onChange={(e) => setTestRecipient(e.target.value)}
                  placeholder="you@example.com"
                  autoComplete="off"
                  sx={{ flex: 1 }}
                />
                <Button
                  variant="contained"
                  startIcon={<SendHorizonal size={18} />}
                  onClick={onSendTest}
                  disabled={sendTest.isPending || testRecipient.trim().length === 0 || hasChanges}
                  sx={{ height: 40, flexShrink: 0 }}
                >
                  {sendTest.isPending ? 'Sending…' : 'Send test'}
                </Button>
              </Stack>
              {hasChanges && (
                <Typography color="text.secondary" sx={{ fontSize: 13, mt: 1 }}>
                  Save your changes before sending a test message.
                </Typography>
              )}
              {testResult && (
                <Alert severity={TEST_SEVERITY[testResult.status]} sx={{ mt: 1.5 }}>
                  {testResult.detail}
                </Alert>
              )}
              {testError && (
                <Alert severity="error" sx={{ mt: 1.5 }}>
                  {testError}
                </Alert>
              )}
            </Box>
          </Stack>
        </SectionCard>

        {error && <Alert severity="error">{error}</Alert>}

        <Divider />
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
          <Button
            type="submit"
            variant="contained"
            disabled={!hasChanges || updateSettings.isPending}
          >
            {updateSettings.isPending ? 'Saving…' : 'Save changes'}
          </Button>
          <Button
            type="button"
            color="inherit"
            disabled={!hasChanges || updateSettings.isPending}
            onClick={discard}
          >
            Discard
          </Button>
          <Typography color="text.secondary" sx={{ fontSize: 14 }}>
            {hasChanges
              ? `${changed.length} unsaved change${changed.length === 1 ? '' : 's'}.`
              : 'No unsaved changes.'}
          </Typography>
          <Box sx={{ flex: 1 }} />
          {updateSettings.isPending && <LinearProgress sx={{ width: 120 }} />}
        </Stack>
      </Stack>

      <AdminToast toast={toast} onClose={clear} />
    </Box>
  );
}
