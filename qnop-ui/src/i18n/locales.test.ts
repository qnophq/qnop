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

import { afterEach, describe, expect, it } from 'vitest';
import i18n from '.';
import {
  DEVICE_LOCALE_STORAGE_KEY,
  readDeviceLocale,
  resolveLocale,
  toSupportedLocale,
  writeDeviceLocale,
} from './locales';

describe('resolveLocale', () => {
  it('prefers the stored user preference over everything else', () => {
    expect(
      resolveLocale({
        preferred: 'de',
        device: 'en',
        applicationDefault: 'en',
        browser: ['en-US'],
      }),
    ).toBe('de');
  });

  it('falls back to the device choice when the user has no preference', () => {
    expect(resolveLocale({ preferred: '', device: 'de', applicationDefault: 'en' })).toBe('de');
  });

  it('falls back to the application default, then the browser, then English', () => {
    expect(resolveLocale({ preferred: '', applicationDefault: 'de', browser: ['en-US'] })).toBe(
      'de',
    );
    expect(resolveLocale({ applicationDefault: '', browser: ['de-AT', 'en'] })).toBe('de');
    expect(resolveLocale({ browser: ['ja-JP'] })).toBe('en');
    expect(resolveLocale({})).toBe('en');
  });

  it('skips a step that names a language the UI does not ship', () => {
    // `it` is a valid preference for mail templates, but there is no Italian catalog.
    expect(resolveLocale({ preferred: 'it', applicationDefault: 'de' })).toBe('de');
    expect(resolveLocale({ applicationDefault: 'it', browser: ['it-IT', 'de-DE'] })).toBe('de');
  });
});

describe('toSupportedLocale', () => {
  it('narrows region tags and casing to the primary language', () => {
    expect(toSupportedLocale('de-AT')).toBe('de');
    expect(toSupportedLocale('es-MX')).toBe('es');
    expect(toSupportedLocale('fr-CA')).toBe('fr');
    expect(toSupportedLocale('EN_gb')).toBe('en');
    expect(toSupportedLocale('it')).toBeUndefined();
    expect(toSupportedLocale('')).toBeUndefined();
    expect(toSupportedLocale(undefined)).toBeUndefined();
  });
});

describe('device locale storage', () => {
  afterEach(() => localStorage.clear());

  it('round-trips a supported choice and ignores garbage', () => {
    expect(readDeviceLocale()).toBeUndefined();
    writeDeviceLocale('de');
    expect(localStorage.getItem(DEVICE_LOCALE_STORAGE_KEY)).toBe('de');
    expect(readDeviceLocale()).toBe('de');
    localStorage.setItem(DEVICE_LOCALE_STORAGE_KEY, 'klingon');
    expect(readDeviceLocale()).toBeUndefined();
  });
});

describe('missing keys', () => {
  it('fall back to the English catalog for a language without a translation', () => {
    expect(i18n.t('language.label', { lng: 'it' })).toBe('Language');
  });
});
