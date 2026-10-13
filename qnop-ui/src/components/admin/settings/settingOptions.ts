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

import type { ParseKeys } from 'i18next';
import { SUPPORTED_LOCALES } from '../../../i18n/locales';
import { nativeLocaleName } from '../../../utils/locale';

type AdminKey = ParseKeys<'admin'>;

/**
 * A dropdown option: a catalog key for words that translate, or a verbatim label
 * for proper names (language endonyms, product names) and raw enum values.
 */
export type SelectOption = { value: string; labelKey: AdminKey } | { value: string; label: string };

/**
 * Curated dropdown labels keyed by setting key. ENUM option *sets* now come from
 * the API contract (`allowedValues`); this map only supplies nicer labels and
 * covers STRING settings with a known, closed value set (e.g. the default
 * language) that are not modelled as ENUM server-side. Language names stay in
 * their own language, product names stay as they are.
 */
const SEVERITY_OPTIONS: SelectOption[] = [
  { value: 'info', labelKey: 'settings.options.severity.info' },
  { value: 'warning', labelKey: 'settings.options.severity.warning' },
  { value: 'critical', labelKey: 'settings.options.severity.critical' },
];

export const SELECT_OPTIONS: Record<string, SelectOption[]> = {
  // Every UI language, each named in itself.
  'general.default_language': SUPPORTED_LOCALES.map((value) => ({
    value,
    label: nativeLocaleName(value),
  })),
  'tracking.provider': [
    { value: 'none', labelKey: 'settings.options.trackingProvider.none' },
    { value: 'matomo', label: 'Matomo' },
    { value: 'plausible', label: 'Plausible' },
    { value: 'umami', label: 'Umami' },
    { value: 'posthog', label: 'PostHog' },
    { value: 'pirsch', label: 'Pirsch' },
  ],
  'tracking.forward_client_ip': [
    { value: 'anonymized', labelKey: 'settings.options.forwardClientIp.anonymized' },
    { value: 'none', labelKey: 'settings.options.forwardClientIp.none' },
    { value: 'full', labelKey: 'settings.options.forwardClientIp.full' },
  ],
  'auth.self_registration_default_role': [
    { value: 'MEMBER', labelKey: 'roles.global.MEMBER' },
    { value: 'AUDITOR', labelKey: 'roles.global.AUDITOR' },
  ],
  'banner.login_severity': SEVERITY_OPTIONS,
  'banner.app_severity': SEVERITY_OPTIONS,
};
