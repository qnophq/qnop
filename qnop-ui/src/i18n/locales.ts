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

/**
 * The UI languages qnop ships catalogs for (issue #464, ADR-0063). Adding a language means adding
 * its code here plus a `src/i18n/<code>/` catalog folder; everything else (switchers, resolution,
 * MUI locales) derives from this list.
 */
export const SUPPORTED_LOCALES = ['en', 'de', 'es'] as const;

export type AppLocale = (typeof SUPPORTED_LOCALES)[number];

/** The last resort of the resolution chain, and the catalog every other language falls back to. */
export const FALLBACK_LOCALE: AppLocale = 'en';

/** The per-user setting key holding the preferred UI language (ISO 639-1, empty = unset). */
export const PREFERRED_LANGUAGE_SETTING_KEY = 'preferred_language';

/** localStorage key for the device-wide choice made on the pre-login pages. */
export const DEVICE_LOCALE_STORAGE_KEY = 'qnop.locale';

/**
 * The BCP 47 tag each UI language formats with. English keeps the day-month-year `en-GB` style the
 * UI has always shown (ADR-0041 era), rather than the US month-first order.
 */
const INTL_TAGS: Record<AppLocale, string> = { en: 'en-GB', de: 'de-DE', es: 'es-ES' };

export function intlLocale(locale: AppLocale): string {
  return INTL_TAGS[locale];
}

/** Narrows a raw value (setting, storage, browser tag like `de-AT`) to a supported locale. */
export function toSupportedLocale(raw: string | null | undefined): AppLocale | undefined {
  if (!raw) return undefined;
  const primary = raw.trim().toLowerCase().split(/[-_]/)[0];
  return (SUPPORTED_LOCALES as readonly string[]).includes(primary)
    ? (primary as AppLocale)
    : undefined;
}

export interface LocaleSources {
  /** The signed-in user's stored `preferred_language`; empty/undefined when unset. */
  preferred?: string | null;
  /** The device-wide choice from the pre-login switcher (localStorage). */
  device?: string | null;
  /** The application default `general.defaultLanguage` from `/config`. */
  applicationDefault?: string | null;
  /** The browser's preference list, most preferred first (`navigator.languages`). */
  browser?: readonly string[];
}

/**
 * Resolves the active UI language (issue #464): stored user preference → device choice →
 * application default → browser languages → English. Each step counts only when it names a
 * language the UI ships, so an unsupported preference (e.g. `fr`, valid for mail templates) falls
 * through instead of leaving the UI untranslated.
 */
export function resolveLocale(sources: LocaleSources): AppLocale {
  const candidates = [
    sources.preferred,
    sources.device,
    sources.applicationDefault,
    ...(sources.browser ?? []),
  ];
  for (const candidate of candidates) {
    const locale = toSupportedLocale(candidate);
    if (locale) return locale;
  }
  return FALLBACK_LOCALE;
}

/** The device-wide choice, or `undefined` when none was made or storage is unavailable. */
export function readDeviceLocale(): AppLocale | undefined {
  try {
    return toSupportedLocale(localStorage.getItem(DEVICE_LOCALE_STORAGE_KEY));
  } catch {
    return undefined;
  }
}

export function writeDeviceLocale(locale: AppLocale): void {
  try {
    localStorage.setItem(DEVICE_LOCALE_STORAGE_KEY, locale);
  } catch {
    // Persistence is best-effort; the choice still applies for this session.
  }
}
