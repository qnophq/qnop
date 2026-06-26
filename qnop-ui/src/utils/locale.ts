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

const LANGUAGE_NAMES =
  typeof Intl.DisplayNames === 'function'
    ? new Intl.DisplayNames(['en'], { type: 'language' })
    : null;

/**
 * Human-readable language name for a BCP-47 locale tag ("en" → "English",
 * "de-AT" → "Austrian German"), falling back to the upper-cased tag for
 * unknown or unsupported locales. Kept locale-agnostic so the per-language
 * mail templates (issue #144) read clearly once more languages are added.
 */
export function localeDisplayName(locale: string): string {
  if (!locale) return '';
  try {
    return LANGUAGE_NAMES?.of(locale) ?? locale.toUpperCase();
  } catch {
    return locale.toUpperCase();
  }
}

/** Compact badge code for a locale: the primary subtag, upper-cased ("en-GB" → "EN"). */
export function localeShortCode(locale: string): string {
  const primary = locale.split('-')[0];
  return (primary || locale).toUpperCase();
}
