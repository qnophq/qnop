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
 * Human-readable language name for a BCP-47 locale tag, written in `displayLocale` ("de" in "en" →
 * "German", in "fr" → "allemand"), falling back to the upper-cased tag for unknown or unsupported
 * locales. Pass the active UI language so the name reads in the admin's own language.
 */
export function localeDisplayName(locale: string, displayLocale = 'en'): string {
  if (!locale) return '';
  try {
    return (
      new Intl.DisplayNames([displayLocale], { type: 'language' }).of(locale) ??
      locale.toUpperCase()
    );
  } catch {
    return locale.toUpperCase();
  }
}

/** Compact badge code for a locale: the primary subtag, upper-cased ("en-GB" → "EN"). */
export function localeShortCode(locale: string): string {
  const primary = locale.split('-')[0];
  return (primary || locale).toUpperCase();
}

/**
 * A language's name in that language, capitalised for a menu ("Deutsch", "English") — how a
 * language switcher lists its options, so a reader finds theirs whatever is active (issue #464).
 */
export function nativeLocaleName(locale: string): string {
  try {
    const name = new Intl.DisplayNames([locale], { type: 'language' }).of(locale) ?? locale;
    return name.charAt(0).toLocaleUpperCase(locale) + name.slice(1);
  } catch {
    return locale.toUpperCase();
  }
}
