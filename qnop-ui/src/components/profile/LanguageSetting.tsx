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

import Box from '@mui/material/Box';
import Divider from '@mui/material/Divider';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Languages } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { SUPPORTED_LOCALES, type AppLocale } from '../../i18n/locales';
import { nativeLocaleName } from '../../utils/locale';

/** The select value that clears the stored preference (follow the workspace default). */
const FOLLOW_DEFAULT = '';

interface LanguageSettingProps {
  /** The user's stored choice, or `undefined` while following the default. */
  explicit: AppLocale | undefined;
  /** What following the default resolves to on this device, named in the "default" option. */
  inherited: AppLocale;
  saving: boolean;
  /** Called with a locale, or `''` to clear the preference. */
  onChange: (value: AppLocale | '') => void;
}

/**
 * The profile's UI-language preference (issue #464): pick a language, or follow the workspace
 * default. Sits next to the time-zone setting it mirrors; language ≠ time zone (ADR-0041).
 */
export function LanguageSetting({ explicit, inherited, saving, onChange }: LanguageSettingProps) {
  const { t } = useTranslation();
  const theme = useTheme();
  const brand = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3 } }}>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
        <Box
          aria-hidden
          sx={{
            display: 'grid',
            placeItems: 'center',
            width: 38,
            height: 38,
            borderRadius: '11px',
            color: brand,
            bgcolor: alpha(brand, dark ? 0.18 : 0.1),
            border: `1px solid ${alpha(brand, 0.3)}`,
          }}
        >
          <Languages size={19} />
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography sx={{ fontSize: 16, fontWeight: 600 }}>{t('language.label')}</Typography>
          <Typography color="text.secondary" sx={{ fontSize: 14, mt: 0.25 }}>
            {t('language.description')}
          </Typography>
        </Box>
      </Stack>

      <Divider sx={{ my: 2 }} />

      <TextField
        select
        fullWidth
        size="small"
        label={t('language.label')}
        value={explicit ?? FOLLOW_DEFAULT}
        disabled={saving}
        // The "follow the default" option's value is '', which MUI renders as blank unless told
        // to display it — and the label must stay shrunk above it.
        slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
        onChange={(event) => onChange(event.target.value as AppLocale | '')}
      >
        <MenuItem value={FOLLOW_DEFAULT}>
          {t('language.followDefault', { language: nativeLocaleName(inherited) })}
        </MenuItem>
        {SUPPORTED_LOCALES.map((locale) => (
          <MenuItem key={locale} value={locale} lang={locale}>
            {nativeLocaleName(locale)}
          </MenuItem>
        ))}
      </TextField>

      {!explicit && (
        <Typography color="text.secondary" sx={{ fontSize: 12.5, mt: 1.5 }}>
          {t('language.followingDefault')}
        </Typography>
      )}
    </Paper>
  );
}
