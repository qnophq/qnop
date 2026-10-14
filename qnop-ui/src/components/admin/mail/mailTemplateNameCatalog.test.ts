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
import enAdmin from '../../../i18n/en/admin.json';
import { SUPPORTED_LOCALES } from '../../../i18n/locales';
import { mailTemplateCatalogId } from './mailTemplateName';

// Read from the backend source, as settingCatalog.test.ts does: the UI is tested inside the
// monorepo checkout, so a missing file fails loudly instead of letting the catalog drift.
const keySource = readFileSync(
  resolve(
    import.meta.dirname,
    '../../../../../qnop-core/src/main/java/io/qnop/service/mail/MailTemplateKey.java',
  ),
  'utf8',
);

/** Every template the backend declares: `NAME(\n "some.key",\n "Friendly name", …`. */
const backendNames = new Map(
  [...keySource.matchAll(/^ {2}[A-Z_]+\(\s*"([^"]+)",\s*"([^"]+)",/gm)].map((match) => [
    match[1],
    match[2],
  ]),
);

/** Every language's admin catalog, so a newly supported language is checked too. */
const adminCatalogs = import.meta.glob<{ mailTemplates: { names?: Record<string, string> } }>(
  '../../../i18n/*/admin.json',
  { eager: true, import: 'default' },
);

function names(locale: string): Record<string, string> {
  return adminCatalogs[`../../../i18n/${locale}/admin.json`]?.mailTemplates.names ?? {};
}

/**
 * The admin mail pages word each template's name from `admin:mailTemplates.names` (issue #861).
 * A template without an entry would still render — in English, from the server's friendlyName —
 * so this keeps the catalog complete instead.
 */
describe('mail template name catalog', () => {
  it('finds the backend templates', () => {
    expect(backendNames.size).toBeGreaterThan(10);
    expect(backendNames.get('auth.password_reset')).toBe('Password reset');
  });

  it.each(SUPPORTED_LOCALES.map((locale) => [locale, names(locale)] as const))(
    '%s names every backend template',
    (_locale, catalog) => {
      const missing = [...backendNames.keys()].filter(
        (key) => !catalog[mailTemplateCatalogId(key)]?.trim(),
      );
      expect(missing).toEqual([]);
    },
  );

  it('has no names for templates the backend no longer declares', () => {
    const known = new Set([...backendNames.keys()].map(mailTemplateCatalogId));
    expect(Object.keys(enAdmin.mailTemplates.names).filter((id) => !known.has(id))).toEqual([]);
  });

  it('keeps the English names identical to the backend friendly names', () => {
    // The catalog wins over the server's friendlyName, so it must not drift from it.
    const catalog = names('en');
    const drifted = [...backendNames].filter(
      ([key, name]) => catalog[mailTemplateCatalogId(key)] !== name,
    );
    expect(drifted).toEqual([]);
  });
});
