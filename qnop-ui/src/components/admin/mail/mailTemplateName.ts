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
 * The catalog id of a mail template key under `admin:mailTemplates.names`. i18next splits keys on
 * `.`, so every `.` of the template key becomes `__` (e.g. `auth.password_reset` →
 * `auth__password_reset`). Template keys only ever use single underscores, so the mapping is
 * unambiguous.
 */
export function mailTemplateCatalogId(key: string): string {
  return key.replaceAll('.', '__');
}

/**
 * The display name of a mail template (issue #861): from the `admin` catalog
 * (`mailTemplates.names.<id>`), falling back to the server's `friendlyName` — so a template the
 * backend (or an extension) adds later still renders, in English, until it gets a catalog entry.
 * The English catalog mirrors `MailTemplateKey`; `mailTemplateNameCatalog.test.ts` fails when the
 * two drift apart.
 */
export function useMailTemplateName() {
  const { t, i18n } = useTranslation('admin');
  return useMemo(() => {
    // The key is dynamic (a server template key), so exists() checks it instead of the key types.
    const translate = t as unknown as (key: string) => string;
    return (template: { key: string; friendlyName: string }): string => {
      const catalogKey = `admin:mailTemplates.names.${mailTemplateCatalogId(template.key)}`;
      return i18n.exists(catalogKey) ? translate(catalogKey) : template.friendlyName;
    };
  }, [t, i18n]);
}
