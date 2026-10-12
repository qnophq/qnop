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

import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import enCommon from './en/common.json';
import { FALLBACK_LOCALE, type AppLocale } from './locales';

export const DEFAULT_NAMESPACE = 'common';

/**
 * English is bundled: it is the fallback for every missing key and what most first paints need.
 * Every other language is code-split and fetched on first use (issue #464, ADR-0063), so the
 * initial bundle does not grow with each language added.
 */
export const bundledResources = { en: { common: enCommon } } as const;

const lazyCatalogs = import.meta.glob<{ default: Record<string, unknown> }>('./*/*.json');

void i18n.use(initReactI18next).init({
  lng: FALLBACK_LOCALE,
  fallbackLng: FALLBACK_LOCALE,
  ns: [DEFAULT_NAMESPACE],
  defaultNS: DEFAULT_NAMESPACE,
  resources: bundledResources,
  partialBundledLanguages: true,
  // React already escapes rendered values.
  interpolation: { escapeValue: false },
  returnNull: false,
  initAsync: false,
});

/** Loads every namespace of a language that is not bundled yet. Idempotent. */
export async function ensureLocaleLoaded(locale: AppLocale): Promise<void> {
  const prefix = `./${locale}/`;
  const pending = Object.entries(lazyCatalogs)
    .filter(([path]) => path.startsWith(prefix))
    .map(async ([path, load]) => {
      const namespace = path.slice(prefix.length, -'.json'.length);
      if (i18n.hasResourceBundle(locale, namespace)) return;
      const catalog = await load();
      i18n.addResourceBundle(locale, namespace, catalog.default, true, true);
    });
  await Promise.all(pending);
}

// Each applyLocale call takes a ticket; only the newest may switch. Without it, a slow catalog
// chunk for a language the user already moved away from (a fast flip, or the optimistic rollback
// after a failed save) would land last and switch the UI back to it.
let latestRequest = 0;

/**
 * Makes {@code locale} the active UI language: loads its catalogs, switches i18next (every
 * `useTranslation` consumer re-renders) and sets `<html lang>` for assistive technology. Resolves
 * `false` without switching when a newer call superseded this one while its catalogs loaded.
 */
export async function applyLocale(locale: AppLocale): Promise<boolean> {
  const request = ++latestRequest;
  await ensureLocaleLoaded(locale);
  if (request !== latestRequest) return false;
  if (i18n.language !== locale) {
    await i18n.changeLanguage(locale);
  }
  document.documentElement.lang = locale;
  return true;
}

export default i18n;
