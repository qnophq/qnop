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

import {
  PREFERRED_LANGUAGE_SETTING_KEY,
  resolveLocale,
  toSupportedLocale,
  type AppLocale,
} from '../../i18n/locales';
import { useUiStore } from '../../stores/uiStore';
import { useConfig } from './useConfig';
import { useUserSettingValue } from './useUserSettings';

function browserLanguages(): readonly string[] {
  if (typeof navigator === 'undefined') return [];
  return navigator.languages?.length ? navigator.languages : [navigator.language];
}

/**
 * The UI language the viewer should see (issue #464): the signed-in user's stored
 * `preferred_language` → the device choice from the pre-login switcher → the application default
 * (`general.defaultLanguage` on `/config`) → the browser languages → English. LanguageSync applies
 * it; components render through `useTranslation`/`useFormatters`, never this hook directly.
 */
export function useDisplayLanguage(): AppLocale {
  const { data: config } = useConfig();
  const preferred = useUserSettingValue(PREFERRED_LANGUAGE_SETTING_KEY);
  const device = useUiStore((s) => s.deviceLocale);
  return resolveLocale({
    preferred,
    device,
    applicationDefault: config?.general.defaultLanguage,
    browser: browserLanguages(),
  });
}

/**
 * The language a user without a stored preference gets on this device — what "follow the default"
 * resolves to, so the profile setting can name it.
 */
export function useInheritedLanguage(): AppLocale {
  const { data: config } = useConfig();
  const device = useUiStore((s) => s.deviceLocale);
  return resolveLocale({
    device,
    applicationDefault: config?.general.defaultLanguage,
    browser: browserLanguages(),
  });
}

/** The stored preference as a supported locale, or `undefined` while following the default. */
export function useExplicitLanguage(): AppLocale | undefined {
  return toSupportedLocale(useUserSettingValue(PREFERRED_LANGUAGE_SETTING_KEY));
}
