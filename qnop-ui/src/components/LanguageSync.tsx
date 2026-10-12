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

import { useEffect } from 'react';
import { useDisplayLanguage } from '../api/hooks/useDisplayLanguage';
import { applyLocale } from '../i18n';
import { useUiStore } from '../stores/uiStore';

/**
 * Resolves the active UI language (issue #464, ADR-0063) and applies it: loads the catalogs,
 * switches i18next, sets `<html lang>`, and mirrors the locale into the UI store for the
 * formatters. Mounted once near the app root, inside the QueryClientProvider, like TimezoneSync.
 * Renders nothing.
 */
export function LanguageSync() {
  const locale = useDisplayLanguage();
  const setLocale = useUiStore((s) => s.setLocale);

  useEffect(() => {
    let cancelled = false;
    applyLocale(locale)
      .then(() => {
        if (!cancelled) setLocale(locale);
      })
      .catch(() => {
        // A catalog chunk failed to load (offline, stale deploy): stay on the current language
        // rather than half-switching; English remains the fallback for every key.
      });
    return () => {
      cancelled = true;
    };
  }, [locale, setLocale]);

  return null;
}
