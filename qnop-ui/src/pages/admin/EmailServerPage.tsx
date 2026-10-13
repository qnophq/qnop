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
import type { ParseKeys } from 'i18next';
import { AtSign, SendHorizonal, Server, Zap } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { AdminSetting, SendTestEmailResponse } from '../../api/generated';
import { useSettings, useUpdateSettings } from '../../api/hooks/useSettings';
import { useSendTestEmail } from '../../api/hooks/useMailTemplates';
import { useAuthStore } from '../../stores/authStore';
import { PasswordField } from '../../components/auth/PasswordField';
import { SectionCard } from '../../components/admin/layout/SectionCard';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import { ToneBadge } from '../../components/admin/ToneBadge';
import { useSettingText } from '../../components/admin/settings/settingText';
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

const TEST_SEVERITY: Record<SendTestEmailResponse['status'], 'success' | 'info' | 'error'> = {
  SENT: 'success',
  SKIPPED: 'info',
  FAILED: 'error',
};

/** A key in the `admin` catalog. */
type AdminKey = ParseKeys<'admin'>;

/** A failed save or test send: the error itself and the catalog key of its fallback message. */
interface FailedRequest {
  cause: unknown;
  fallbackKey: AdminKey;
}

/** The test-send problem: a failed request, or a recipient that is not an address. */
type TestError = FailedRequest | { invalidRecipient: true };

/** Secrets start blank (the API masks them); everything else baselines on its value. */
function baselineOf(setting: AdminSetting): string {
  return setting.sensitive ? '' : (setting.value ?? '');
}

/**
 * SMTP administration (issue #142), the Server tab of the email admin area
 * (#525): outgoing-mail connection, sender identity, and delivery status in
 * three focused cards, with an inline test-send. Only the changed keys are
 * PATCHed; the encryption options come from the API contract's
 * `allowedValues`, rendered with curated labels and port hints.
 */
export function EmailServerPage() {
  const { t } = useTranslation(['admin', 'common']);
  const settingText = useSettingText();
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
  const [error, setError] = useState<FailedRequest | null>(null);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const { toast, notify, clear } = useToast();
  // Default the test recipient to the signed-in admin (issue #146); still editable.
  const adminEmail = useAuthStore((s) => s.email);
  const [testRecipient, setTestRecipient] = useState(adminEmail ?? '');
  const [testResult, setTestResult] = useState<SendTestEmailResponse | null>(null);
  const [testError, setTestError] = useState<TestError | null>(null);

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

  // Client-side field errors mirroring the server's SMTP constraints, as catalog keys.
  const clientErrors = useMemo(() => {
    const valueAt = (key: string) => edits[key] ?? baselines[key] ?? '';
    const map: Record<string, AdminKey> = {};
    if (enabled && valueAt(SMTP_KEYS.host).trim() === '') {
      map[SMTP_KEYS.host] = 'mail.validation.hostRequired';
    }
    const port = valueAt(SMTP_KEYS.port);
    if (port.trim() !== '' && !isPort(port)) {
      map[SMTP_KEYS.port] = 'mail.validation.port';
    }
    const from = valueAt(SMTP_KEYS.from);
    if (from.trim() !== '' && !isEmail(from)) {
      map[SMTP_KEYS.from] = 'mail.validation.email';
    }
    return map;
  }, [edits, baselines, enabled]);

  const fieldError = (key: string): string | undefined => {
    if (serverErrors[key]) return serverErrors[key];
    const clientKey = submitAttempted ? clientErrors[key] : undefined;
    return clientKey ? t(clientKey) : undefined;
  };

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
      notify(t('mail.server.saved'));
    } catch (err) {
      const serverFieldErrors = apiFieldErrors(err);
      if (Object.keys(serverFieldErrors).length > 0) {
        setServerErrors(serverFieldErrors);
      } else {
        setError({ cause: err, fallbackKey: 'mail.server.saveFailed' });
      }
    }
  };

  const onSendTest = async () => {
    setTestError(null);
    setTestResult(null);
    if (!isEmail(testRecipient)) {
      setTestError({ invalidRecipient: true });
      return;
    }
    try {
      setTestResult(await sendTest.mutateAsync(testRecipient.trim()));
    } catch (err) {
      setTestError({ cause: err, fallbackKey: 'mail.test.failed' });
    }
  };

  /** A standard text input for a string/number SMTP field, wired to the draft. */
  const textField = (key: string, extra?: Partial<TextFieldProps>) => {
    const setting = byKey[key];
    if (!setting) return null;
    const err = fieldError(key);
    return (
      <TextField
        label={SMTP_FIELD[key] ? t(SMTP_FIELD[key].labelKey) : key}
        value={valueOf(key)}
        onChange={(e) => setValue(key, e.target.value)}
        placeholder={SMTP_FIELD[key]?.placeholder}
        error={Boolean(err)}
        helperText={err ?? settingText.description(setting)}
        fullWidth
        {...extra}
      />
    );
  };

  if (isLoading) {
    return (
      <Typography color="text.secondary" sx={{ p: 1, fontSize: 14 }}>
        {t('common:loading')}
      </Typography>
    );
  }
  if (isError) {
    return <Alert severity="error">{t('mail.server.loadFailed')}</Alert>;
  }

  const testErrorMessage =
    testError === null
      ? null
      : 'invalidRecipient' in testError
        ? t('mail.validation.recipient')
        : apiErrorMessage(testError.cause, t(testError.fallbackKey));

  const passwordSetting = byKey[SMTP_KEYS.password];
  const passwordSet = passwordSetting?.value === '***';

  return (
    <Box component="form" onSubmit={onSubmit} noValidate>
      <Stack spacing={3}>
        <SectionCard
          icon={Server}
          title={t('mail.server.connection.title')}
          description={t('mail.server.connection.description')}
        >
          <Stack spacing={2.5} sx={{ maxWidth: 480 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Box sx={{ flex: 1 }}>{textField(SMTP_KEYS.host)}</Box>
              <Box sx={{ width: { sm: 150 } }}>{textField(SMTP_KEYS.port, { type: 'number' })}</Box>
            </Stack>

            {byKey[SMTP_KEYS.encryption] && (
              <TextField
                select
                label={t(SMTP_FIELD[SMTP_KEYS.encryption].labelKey)}
                value={valueOf(SMTP_KEYS.encryption)}
                onChange={(e) => setValue(SMTP_KEYS.encryption, e.target.value)}
                helperText={settingText.description(byKey[SMTP_KEYS.encryption])}
                fullWidth
                slotProps={{
                  select: {
                    renderValue: (value) => {
                      const meta = ENCRYPTION_META[String(value)];
                      return meta ? t(meta.labelKey) : String(value);
                    },
                  },
                }}
              >
                {encryptionOptions.map((option) => {
                  const meta = ENCRYPTION_META[option];
                  return (
                    <MenuItem key={option} value={option}>
                      <Box>
                        <Typography sx={{ fontSize: 14 }}>
                          {meta ? t(meta.labelKey) : option}
                        </Typography>
                        {meta && (
                          <Typography color="text.secondary" sx={{ fontSize: 12 }}>
                            {t(meta.hintKey)}
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
                label={t(SMTP_FIELD[SMTP_KEYS.password].labelKey)}
                value={valueOf(SMTP_KEYS.password)}
                onChange={(next) => setValue(SMTP_KEYS.password, next)}
                autoComplete="new-password"
                helperText={
                  passwordSet
                    ? t('mail.server.passwordStored')
                    : settingText.description(passwordSetting)
                }
              />
            )}
          </Stack>
        </SectionCard>

        <SectionCard
          icon={AtSign}
          title={t('mail.server.identity.title')}
          description={t('mail.server.identity.description')}
        >
          <Stack spacing={2.5} sx={{ maxWidth: 480 }}>
            {textField(SMTP_KEYS.from, { type: 'email', autoComplete: 'off' })}
            {textField(SMTP_KEYS.fromName)}
          </Stack>
        </SectionCard>

        <SectionCard
          icon={Zap}
          title={t('mail.server.delivery.title')}
          description={t('mail.server.delivery.description')}
          action={<ToneBadge tone={status.tone} label={t(status.labelKey)} />}
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
                    <Typography sx={{ fontSize: 15 }}>
                      {t('mail.server.delivery.enable')}
                    </Typography>
                    <Typography color="text.secondary" sx={{ fontSize: 13 }}>
                      {t(status.detailKey)}
                    </Typography>
                  </Box>
                }
                sx={{ alignItems: 'flex-start', m: 0, gap: 1 }}
              />
            )}

            <Divider />

            <Box>
              <Typography sx={{ fontSize: 15, fontWeight: 600 }}>
                {t('mail.server.delivery.testTitle')}
              </Typography>
              <Typography color="text.secondary" sx={{ fontSize: 13, mt: 0.25, mb: 1.5 }}>
                {t('mail.server.delivery.testDescription')}
              </Typography>
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1.5}
                sx={{ alignItems: { sm: 'flex-start' } }}
              >
                <TextField
                  label={t('mail.test.recipient')}
                  type="email"
                  value={testRecipient}
                  onChange={(e) => setTestRecipient(e.target.value)}
                  placeholder={t('mail.test.recipientPlaceholder')}
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
                  {sendTest.isPending ? t('mail.test.sending') : t('mail.server.delivery.sendTest')}
                </Button>
              </Stack>
              {hasChanges && (
                <Typography color="text.secondary" sx={{ fontSize: 13, mt: 1 }}>
                  {t('mail.server.delivery.saveFirst')}
                </Typography>
              )}
              {testResult && (
                <Alert severity={TEST_SEVERITY[testResult.status]} role="status" sx={{ mt: 1.5 }}>
                  {testResult.detail}
                </Alert>
              )}
              {testErrorMessage && (
                <Alert severity="error" sx={{ mt: 1.5 }}>
                  {testErrorMessage}
                </Alert>
              )}
            </Box>
          </Stack>
        </SectionCard>

        {error && (
          <Alert severity="error">{apiErrorMessage(error.cause, t(error.fallbackKey))}</Alert>
        )}

        <Divider />
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
          <Button
            type="submit"
            variant="contained"
            disabled={!hasChanges || updateSettings.isPending}
          >
            {updateSettings.isPending ? t('mail.server.saving') : t('mail.server.saveChanges')}
          </Button>
          <Button
            type="button"
            color="inherit"
            disabled={!hasChanges || updateSettings.isPending}
            onClick={discard}
          >
            {t('common:actions.discard')}
          </Button>
          <Typography color="text.secondary" sx={{ fontSize: 14 }}>
            {hasChanges
              ? t('mail.server.unsavedChanges', { count: changed.length })
              : t('mail.server.noUnsavedChanges')}
          </Typography>
          <Box sx={{ flex: 1 }} />
          {updateSettings.isPending && <LinearProgress sx={{ width: 120 }} />}
        </Stack>
      </Stack>

      <AdminToast toast={toast} onClose={clear} />
    </Box>
  );
}
