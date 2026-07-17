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
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { BarChart3, Lock, SlidersHorizontal, Upload, type LucideIcon } from 'lucide-react';
import type { AdminSetting } from '../../../api/generated';
import { useSettings, useUpdateSettings } from '../../../api/hooks/useSettings';
import { SectionCard } from '../layout/SectionCard';
import { AdminToast } from '../layout/AdminToast';
import { useToast } from '../layout/useToast';
import { apiErrorMessage, apiFieldErrors } from '../../../utils/apiError';
import { isHttpUrl, isInteger, isIntegerInRange } from '../../../utils/validation';

/** Group prefixes in display order; unknown groups are appended alphabetically. */
const GROUP_ORDER = ['general', 'upload', 'tracking', 'auth'] as const;

const GROUP_LABELS: Record<string, string> = {
  general: 'General',
  upload: 'Uploads',
  tracking: 'Usage tracking',
  auth: 'Authentication',
};

/** Brand-tint section icon per group, matching the Email / SMTP page's language. */
const GROUP_ICONS: Record<string, LucideIcon> = {
  general: SlidersHorizontal,
  upload: Upload,
  tracking: BarChart3,
  auth: Lock,
};

const GROUP_DESCRIPTIONS: Record<string, string> = {
  general: 'Workspace identity, base URL and default language.',
  upload: 'Constraints applied to document uploads.',
  tracking: 'Anonymous, privacy-friendly usage analytics.',
  auth: 'Self-registration and password-reset behaviour.',
};

/**
 * Groups intentionally omitted here because they have a dedicated, richer
 * surface. SMTP lives on the Email / SMTP page (issue #142).
 */
const HIDDEN_GROUPS = new Set<string>(['smtp']);

/**
 * Curated dropdown labels keyed by setting key. ENUM option *sets* now come from
 * the API contract (`allowedValues`); this map only supplies nicer labels and
 * covers STRING settings with a known, closed value set (e.g. the default
 * language) that are not modelled as ENUM server-side.
 */
const SELECT_OPTIONS: Record<string, { value: string; label: string }[]> = {
  'general.default_language': [
    { value: 'en', label: 'English' },
    { value: 'de', label: 'Deutsch' },
  ],
  'tracking.provider': [
    { value: 'none', label: 'None' },
    { value: 'matomo', label: 'Matomo' },
    { value: 'plausible', label: 'Plausible' },
    { value: 'umami', label: 'Umami' },
  ],
  'auth.self_registration_default_role': [
    { value: 'MEMBER', label: 'Member' },
    { value: 'AUDITOR', label: 'Auditor' },
  ],
};

/** Humanises a raw enum value for display, e.g. `starttls` → "Starttls". */
function humaniseOption(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

/**
 * The dropdown options for a setting, if it should render as a select: a curated
 * label list when one exists, otherwise the API's `allowedValues` for ENUMs.
 * Returns `undefined` for free-text fields.
 */
function optionsFor(setting: AdminSetting): { value: string; label: string }[] | undefined {
  const curated = SELECT_OPTIONS[setting.key];
  if (curated) {
    return curated;
  }
  if (setting.type === 'ENUM' && setting.allowedValues && setting.allowedValues.length > 0) {
    return setting.allowedValues.map((value) => ({ value, label: humaniseOption(value) }));
  }
  return undefined;
}

/**
 * Per-key client-side rules mirroring the server's constraints, for instant
 * feedback before a save round-trips. The server stays the authority; these just
 * catch the obvious mistakes at the field.
 */
const SETTING_RULES: Record<string, { test: (value: string) => boolean; message: string }> = {
  'general.base_url': {
    test: isHttpUrl,
    message: 'Enter a valid http(s) URL, e.g. https://qnop.example.',
  },
  'upload.document_max_file_size_mb': {
    test: (value) => isIntegerInRange(value, 1, 1024),
    message: 'Enter a whole number between 1 and 1024.',
  },
  'upload.attachment_max_file_size_mb': {
    test: (value) => isIntegerInRange(value, 1, 50),
    message: 'Enter a whole number between 1 and 50.',
  },
  'auth.password_reset_token_ttl_minutes': {
    test: (value) => isIntegerInRange(value, 1, 1440),
    message: 'Enter a whole number of minutes between 1 and 1440.',
  },
};

/**
 * The client-side validation error for a setting value, or undefined when valid.
 * Blank values pass — an empty field means "keep the default", and the server is
 * the authority on what is truly required.
 */
function validateSetting(setting: AdminSetting, value: string): string | undefined {
  if (value.trim() === '') {
    return undefined;
  }
  const rule = SETTING_RULES[setting.key];
  if (rule) {
    return rule.test(value) ? undefined : rule.message;
  }
  if (setting.type === 'INTEGER' && !isInteger(value)) {
    return 'Enter a whole number.';
  }
  return undefined;
}

/** The baseline value used for dirty-tracking: secrets always start blank. */
function baseline(setting: AdminSetting): string {
  return setting.sensitive ? '' : (setting.value ?? '');
}

/** Humanises the last key segment, e.g. `general.application_name` → "Application name". */
function fieldLabel(key: string): string {
  const segment = key.slice(key.lastIndexOf('.') + 1).replace(/_/g, ' ');
  return segment.charAt(0).toUpperCase() + segment.slice(1);
}

function groupOf(key: string): string {
  const dot = key.indexOf('.');
  return dot === -1 ? key : key.slice(0, dot);
}

/**
 * Application settings editor (issue #106): renders the flat settings list as
 * grouped, type-aware controls and PATCHes only the changed keys. Sensitive
 * values are write-only — the field stays blank and is sent only when typed.
 */
export function ApplicationSettingsForm() {
  const { data, isLoading, isError } = useSettings();
  const updateSettings = useUpdateSettings();

  const settings = useMemo(
    () => (data?.settings ?? []).filter((setting) => !HIDDEN_GROUPS.has(groupOf(setting.key))),
    [data],
  );
  // Server values are derived (not state); only the user's edits are held as an
  // overlay, so there is no setState-in-effect to re-seed the form. Clearing the
  // overlay after a save re-bases the form on the freshly stored values.
  const baselines = useMemo(() => {
    const map: Record<string, string> = {};
    for (const setting of settings) {
      map[setting.key] = baseline(setting);
    }
    return map;
  }, [settings]);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const { toast, notify, clear } = useToast();

  const valueOf = (key: string): string => edits[key] ?? baselines[key] ?? '';
  const changed = useMemo(
    () => Object.keys(edits).filter((key) => edits[key] !== (baselines[key] ?? '')),
    [edits, baselines],
  );

  /** Updates a field's value and drops any now-stale server error for it. */
  const setValue = (key: string, next: string) => {
    setEdits((prev) => ({ ...prev, [key]: next }));
    setServerErrors((prev) => {
      if (!(key in prev)) return prev;
      const rest = { ...prev };
      delete rest[key];
      return rest;
    });
  };

  // Client-side errors for fields the user has touched; surfaced once a save is attempted.
  const clientErrors = useMemo(() => {
    const map: Record<string, string> = {};
    for (const setting of settings) {
      if (!(setting.key in edits)) continue;
      const err = validateSetting(setting, edits[setting.key] ?? '');
      if (err) map[setting.key] = err;
    }
    return map;
  }, [settings, edits]);

  const fieldError = (key: string): string | undefined =>
    serverErrors[key] ?? (submitAttempted ? clientErrors[key] : undefined);

  const discard = () => {
    setEdits({});
    setServerErrors({});
    setSubmitAttempted(false);
  };

  const groups = useMemo(() => {
    const byGroup = new Map<string, AdminSetting[]>();
    for (const setting of settings) {
      const group = groupOf(setting.key);
      const list = byGroup.get(group) ?? [];
      list.push(setting);
      byGroup.set(group, list);
    }
    const known = GROUP_ORDER.filter((group) => byGroup.has(group));
    const extra = [...byGroup.keys()]
      .filter((group) => !GROUP_ORDER.includes(group as never))
      .sort();
    return [...known, ...extra].map((group) => ({ group, items: byGroup.get(group) ?? [] }));
  }, [settings]);

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
      notify('Settings saved.');
    } catch (err) {
      const serverFieldErrors = apiFieldErrors(err);
      if (Object.keys(serverFieldErrors).length > 0) {
        setServerErrors(serverFieldErrors);
      } else {
        setError(apiErrorMessage(err, 'The settings could not be saved.'));
      }
    }
  };

  if (isLoading) {
    return (
      <Typography color="text.secondary" sx={{ p: 1, fontSize: 14 }}>
        Loading…
      </Typography>
    );
  }
  if (isError) {
    return <Alert severity="error">The settings could not be loaded.</Alert>;
  }

  return (
    <Box component="form" onSubmit={onSubmit} noValidate>
      <Stack spacing={3}>
        {groups.map(({ group, items }) => (
          <SectionCard
            key={group}
            icon={GROUP_ICONS[group]}
            title={GROUP_LABELS[group] ?? fieldLabel(group)}
            description={GROUP_DESCRIPTIONS[group]}
          >
            <Stack spacing={2.5}>
              {items.map((setting) => (
                <SettingField
                  key={setting.key}
                  setting={setting}
                  value={valueOf(setting.key)}
                  error={fieldError(setting.key)}
                  onChange={(next) => setValue(setting.key, next)}
                />
              ))}
            </Stack>
          </SectionCard>
        ))}

        {error && <Alert severity="error">{error}</Alert>}

        <Divider />
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
          <Button
            type="submit"
            variant="contained"
            disabled={changed.length === 0 || updateSettings.isPending}
          >
            {updateSettings.isPending ? 'Saving…' : 'Save changes'}
          </Button>
          <Button
            type="button"
            color="inherit"
            disabled={changed.length === 0 || updateSettings.isPending}
            onClick={discard}
          >
            Discard
          </Button>
          <Typography color="text.secondary" sx={{ fontSize: 14 }}>
            {changed.length === 0
              ? 'No unsaved changes.'
              : `${changed.length} unsaved change${changed.length === 1 ? '' : 's'}.`}
          </Typography>
          <Box sx={{ flex: 1 }} />
          {updateSettings.isPending && <LinearProgress sx={{ width: 120 }} />}
        </Stack>
      </Stack>

      <AdminToast toast={toast} onClose={clear} />
    </Box>
  );
}

interface SettingFieldProps {
  setting: AdminSetting;
  value: string;
  error?: string;
  onChange: (next: string) => void;
}

/** Renders a single setting as the control appropriate to its declared type. */
function SettingField({ setting, value, error, onChange }: SettingFieldProps) {
  const label = fieldLabel(setting.key);

  if (setting.type === 'BOOLEAN') {
    return (
      <FormControlLabel
        control={
          <Switch
            checked={value === 'true'}
            onChange={(e) => onChange(e.target.checked ? 'true' : 'false')}
          />
        }
        label={
          <Box>
            <Typography sx={{ fontSize: 15 }}>{label}</Typography>
            <Typography color="text.secondary" sx={{ fontSize: 13 }}>
              {setting.description}
            </Typography>
          </Box>
        }
        sx={{ alignItems: 'flex-start', m: 0, gap: 1 }}
      />
    );
  }

  // A dropdown is used for ENUM settings (options from the API contract's
  // allowedValues) and for STRING settings with a known, closed value set
  // (curated, e.g. the default language).
  const options = optionsFor(setting);
  if (options) {
    return (
      <TextField
        select
        label={label}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        error={Boolean(error)}
        helperText={error ?? setting.description}
        sx={{ maxWidth: 480 }}
      >
        {options.map((option) => (
          <MenuItem key={option.value} value={option.value}>
            {option.label}
          </MenuItem>
        ))}
      </TextField>
    );
  }

  const isPassword = setting.type === 'PASSWORD';
  const baseHelper = isPassword
    ? `${setting.description} Leave blank to keep the current value.`
    : setting.description;
  return (
    <TextField
      label={label}
      type={isPassword ? 'password' : setting.type === 'INTEGER' ? 'number' : 'text'}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={isPassword && setting.value === '***' ? '•••••• (unchanged)' : undefined}
      error={Boolean(error)}
      helperText={error ?? baseHelper}
      autoComplete={isPassword ? 'new-password' : undefined}
      sx={{ maxWidth: 480 }}
    />
  );
}
