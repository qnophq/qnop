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

import { useState } from 'react';
import Alert from '@mui/material/Alert';
import IconButton from '@mui/material/IconButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Snackbar from '@mui/material/Snackbar';
import Tooltip from '@mui/material/Tooltip';
import { Check, Languages } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useUpdateUserSettings } from '../../api/hooks/useUserSettings';
import {
  PREFERRED_LANGUAGE_SETTING_KEY,
  SUPPORTED_LOCALES,
  type AppLocale,
} from '../../i18n/locales';
import { useUiStore } from '../../stores/uiStore';
import { touchTargetSx } from '../../theme/touchTarget';
import { nativeLocaleName } from '../../utils/locale';

interface LanguageMenuProps {
  active: AppLocale;
  onSelect: (locale: AppLocale) => void;
}

/**
 * The compact language menu itself (issue #464). Languages are listed by their own name
 * ("Deutsch"), so a visitor who cannot read the current language still finds theirs.
 */
function LanguageMenu({ active, onSelect }: LanguageMenuProps) {
  const { t } = useTranslation();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  const choose = (locale: AppLocale) => {
    setAnchor(null);
    if (locale !== active) onSelect(locale);
  };

  return (
    <>
      <Tooltip title={t('language.switcherTooltip')}>
        <IconButton
          size="small"
          sx={touchTargetSx}
          aria-label={t('language.switcherTooltip')}
          aria-haspopup="menu"
          aria-expanded={anchor ? 'true' : undefined}
          onClick={(event) => setAnchor(event.currentTarget)}
        >
          <Languages size={18} />
        </IconButton>
      </Tooltip>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      >
        {SUPPORTED_LOCALES.map((locale) => (
          <MenuItem
            key={locale}
            lang={locale}
            selected={locale === active}
            onClick={() => choose(locale)}
          >
            <ListItemIcon sx={{ visibility: locale === active ? 'visible' : 'hidden' }}>
              <Check size={16} />
            </ListItemIcon>
            <ListItemText>{nativeLocaleName(locale)}</ListItemText>
          </MenuItem>
        ))}
      </Menu>
    </>
  );
}

/**
 * The app shell's switcher: saves the choice to the signed-in user's `preferred_language`, so it
 * follows them to every device. Optimistic — the settings cache flips first, so LanguageSync
 * re-renders the UI at once and rolls back on its own if the server refuses.
 */
export function AccountLanguageSwitcher() {
  const { t } = useTranslation();
  const active = useUiStore((s) => s.locale);
  const updateSettings = useUpdateUserSettings();
  const [saveFailed, setSaveFailed] = useState(false);

  return (
    <>
      <LanguageMenu
        active={active}
        onSelect={(locale) =>
          updateSettings.mutate(
            { [PREFERRED_LANGUAGE_SETTING_KEY]: locale },
            { onError: () => setSaveFailed(true) },
          )
        }
      />
      <Snackbar
        open={saveFailed}
        autoHideDuration={5000}
        onClose={() => setSaveFailed(false)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert severity="error" variant="filled" onClose={() => setSaveFailed(false)}>
          {t('language.saveFailed')}
        </Alert>
      </Snackbar>
    </>
  );
}

/**
 * The pre-login switcher: keeps the choice in this browser only (no account yet). It needs no
 * QueryClient, so the sign-in pages stay renderable on their own.
 */
export function DeviceLanguageSwitcher() {
  const active = useUiStore((s) => s.locale);
  const setDeviceLocale = useUiStore((s) => s.setDeviceLocale);
  return <LanguageMenu active={active} onSelect={setDeviceLocale} />;
}
