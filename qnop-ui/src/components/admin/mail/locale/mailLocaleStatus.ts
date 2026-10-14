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
import type { MailTemplateVariant } from '../../../../api/generated';

/**
 * How one template stands in one language (issue #853): customised by an admin, the built-in
 * default of that language, or — when qnop ships nothing in it — the English default standing in.
 */
export type MailLocaleStatus = 'custom' | 'shipped' | 'fallback';

export function mailLocaleStatus(variant: MailTemplateVariant): MailLocaleStatus {
  if (variant.source === 'DATABASE') return 'custom';
  return variant.shipped ? 'shipped' : 'fallback';
}

/** The status names as `admin` keys, for labels, tooltips and the legend. */
export const MAIL_LOCALE_STATUS_LABELS: Record<MailLocaleStatus, ParseKeys<'admin'>> = {
  custom: 'mailTemplates.localeStatus.custom',
  shipped: 'mailTemplates.localeStatus.shipped',
  fallback: 'mailTemplates.localeStatus.fallback',
};
