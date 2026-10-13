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

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * The catalog id of a setting key under `admin:settings.fields`. i18next splits keys on `.`,
 * so every `.` of the setting key becomes `__` (e.g. `smtp.port` → `smtp__port`). Setting keys
 * only ever use single underscores, so the mapping is unambiguous.
 */
export function settingCatalogId(key: string): string {
  return key.replaceAll('.', '__');
}

/**
 * Humanises the last key segment, e.g. `general.application_name` → "Application name". The
 * fallback label for a setting without a catalog entry (and the English label the catalog
 * mirrors byte for byte).
 */
export function fieldLabel(key: string): string {
  const segment = key.slice(key.lastIndexOf('.') + 1).replace(/_/g, ' ');
  return segment.charAt(0).toUpperCase() + segment.slice(1);
}

/**
 * The label and description of an application setting (issue #836): from the `admin` catalog
 * (`settings.fields.<id>.label|description`), falling back to the humanised key and the
 * server's registry description — so a setting the backend adds later still renders, in
 * English, until it gets a catalog entry.
 */
export function useSettingText() {
  const { t, i18n } = useTranslation('admin');
  return useMemo(() => {
    // The key is dynamic (a server setting key), so exists() checks it instead of the key types.
    const translate = t as unknown as (key: string) => string;
    const lookup = (key: string, part: 'label' | 'description'): string | undefined => {
      const catalogKey = `admin:settings.fields.${settingCatalogId(key)}.${part}`;
      return i18n.exists(catalogKey) ? translate(catalogKey) : undefined;
    };
    return {
      label: (key: string) => lookup(key, 'label') ?? fieldLabel(key),
      description: (setting: { key: string; description: string }) =>
        lookup(setting.key, 'description') ?? setting.description,
    };
  }, [t, i18n]);
}
