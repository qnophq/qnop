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

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import deAdmin from '../../../i18n/de/admin.json';
import enAdmin from '../../../i18n/en/admin.json';
import { fieldLabel, settingCatalogId } from './settingText';

// Read from the backend source (Vite refuses `?raw` imports outside the UI project root).
const registrySource = readFileSync(
  resolve(
    import.meta.dirname,
    '../../../../../qnop-core/src/main/java/io/qnop/service/ApplicationSettingKey.java',
  ),
  'utf8',
);

/** Every setting key the backend registry declares: `NAME("some.key", SettingValueType.X, …)`. */
const registryKeys = [...registrySource.matchAll(/\(\s*"([^"]+)",\s*SettingValueType\./g)].map(
  (match) => match[1],
);

type Fields = Record<string, { label?: string; description?: string }>;

/**
 * The settings form reads each setting's label and description from `admin:settings.fields`
 * (issue #836). A backend setting without a catalog entry would still render — in English, from
 * the humanised key and the server description — so this keeps the catalog complete instead.
 */
describe('application setting catalog', () => {
  it('finds the backend registry', () => {
    expect(registryKeys.length).toBeGreaterThan(30);
    expect(registryKeys).toContain('smtp.port');
  });

  it.each([
    ['en', enAdmin.settings.fields as Fields],
    ['de', deAdmin.settings.fields as Fields],
  ])('%s has a label and description for every registry key', (_locale, fields) => {
    const missing = registryKeys.filter((key) => {
      const entry = fields[settingCatalogId(key)];
      return !entry?.label?.trim() || !entry.description?.trim();
    });
    expect(missing).toEqual([]);
  });

  it('has no entries for keys the registry no longer declares', () => {
    const known = new Set(registryKeys.map(settingCatalogId));
    expect(Object.keys(enAdmin.settings.fields).filter((id) => !known.has(id))).toEqual([]);
  });

  it('keeps the English labels identical to the humanised key', () => {
    const fields = enAdmin.settings.fields as Fields;
    const drifted = registryKeys.filter(
      (key) => fields[settingCatalogId(key)]?.label !== fieldLabel(key),
    );
    expect(drifted).toEqual([]);
  });
});
