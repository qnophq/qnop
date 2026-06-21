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
import Paper from '@mui/material/Paper';
import Snackbar from '@mui/material/Snackbar';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import type { AdminSetting } from '../../../api/generated';
import { useSettings, useUpdateSettings } from '../../../api/hooks/useSettings';
import { apiErrorMessage } from '../../../utils/apiError';

/** Group prefixes in display order; unknown groups are appended alphabetically. */
const GROUP_ORDER = ['general', 'upload', 'tracking', 'smtp', 'auth'] as const;

const GROUP_LABELS: Record<string, string> = {
  general: 'General',
  upload: 'Uploads',
  tracking: 'Usage tracking',
  smtp: 'Email (SMTP)',
  auth: 'Authentication',
};

/**
 * Allowed values for ENUM settings. The API does not publish the option list, so
 * known enum keys are enumerated here; an unknown ENUM key falls back to a plain
 * text field rather than rendering an empty dropdown.
 */
const ENUM_OPTIONS: Record<string, { value: string; label: string }[]> = {
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

type Toast = { message: string; severity: 'success' | 'error' } | null;

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

  const settings = useMemo(() => data?.settings ?? [], [data]);
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
  const [toast, setToast] = useState<Toast>(null);

  const valueOf = (key: string): string => edits[key] ?? baselines[key] ?? '';
  const changed = useMemo(
    () => Object.keys(edits).filter((key) => edits[key] !== (baselines[key] ?? '')),
    [edits, baselines],
  );

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
    const patch = Object.fromEntries(changed.map((key) => [key, edits[key]]));
    try {
      await updateSettings.mutateAsync(patch);
      setEdits({});
      setToast({ message: 'Settings saved.', severity: 'success' });
    } catch (err) {
      setError(apiErrorMessage(err, 'The settings could not be saved.'));
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
          <Paper key={group} variant="outlined" sx={{ p: { xs: 2, sm: 3 } }}>
            <Typography variant="h2" sx={{ fontSize: 18, mb: 2 }}>
              {GROUP_LABELS[group] ?? fieldLabel(group)}
            </Typography>
            <Stack spacing={2.5}>
              {items.map((setting) => (
                <SettingField
                  key={setting.key}
                  setting={setting}
                  value={valueOf(setting.key)}
                  onChange={(next) => setEdits((prev) => ({ ...prev, [setting.key]: next }))}
                />
              ))}
            </Stack>
          </Paper>
        ))}

        {error && <Alert severity="error">{error}</Alert>}

        <Divider />
        <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
          <Button
            type="submit"
            variant="contained"
            disabled={changed.length === 0 || updateSettings.isPending}
          >
            {updateSettings.isPending ? 'Saving…' : 'Save changes'}
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

      <Snackbar
        open={toast !== null}
        autoHideDuration={4000}
        onClose={() => setToast(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {toast ? (
          <Alert severity={toast.severity} onClose={() => setToast(null)} variant="filled">
            {toast.message}
          </Alert>
        ) : undefined}
      </Snackbar>
    </Box>
  );
}

interface SettingFieldProps {
  setting: AdminSetting;
  value: string;
  onChange: (next: string) => void;
}

/** Renders a single setting as the control appropriate to its declared type. */
function SettingField({ setting, value, onChange }: SettingFieldProps) {
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

  const enumOptions = setting.type === 'ENUM' ? ENUM_OPTIONS[setting.key] : undefined;
  if (enumOptions) {
    return (
      <TextField
        select
        label={label}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        helperText={setting.description}
        sx={{ maxWidth: 480 }}
      >
        {enumOptions.map((option) => (
          <MenuItem key={option.value} value={option.value}>
            {option.label}
          </MenuItem>
        ))}
      </TextField>
    );
  }

  const isPassword = setting.type === 'PASSWORD';
  return (
    <TextField
      label={label}
      type={isPassword ? 'password' : setting.type === 'INTEGER' ? 'number' : 'text'}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={isPassword && setting.value === '***' ? '•••••• (unchanged)' : undefined}
      helperText={
        isPassword
          ? `${setting.description} Leave blank to keep the current value.`
          : setting.description
      }
      autoComplete={isPassword ? 'new-password' : undefined}
      sx={{ maxWidth: 480 }}
    />
  );
}
