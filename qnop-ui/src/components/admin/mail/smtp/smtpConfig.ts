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
import type { BadgeTone } from '../../ToneBadge';

/** A key in the `admin` catalog — this module names text, the page translates it. */
type AdminKey = ParseKeys<'admin'>;

/** Stable setting keys for the SMTP surface — the single place that knows them. */
export const SMTP_KEYS = {
  enabled: 'smtp.enabled',
  host: 'smtp.host',
  port: 'smtp.port',
  username: 'smtp.username',
  password: 'smtp.password',
  encryption: 'smtp.encryption',
  from: 'smtp.from',
  fromName: 'smtp.from_name',
} as const;

/** Every key the Email page owns; the generic settings form hides this group. */
export const SMTP_GROUP_PREFIX = 'smtp';

/**
 * Label keys and placeholders for the SMTP fields (the API description is the helper text).
 * The placeholders are sample technical values, not prose, so they stay untranslated.
 */
export const SMTP_FIELD: Record<string, { labelKey: AdminKey; placeholder?: string }> = {
  [SMTP_KEYS.host]: { labelKey: 'mail.fields.host', placeholder: 'smtp.example.com' },
  [SMTP_KEYS.port]: { labelKey: 'mail.fields.port', placeholder: '587' },
  [SMTP_KEYS.username]: { labelKey: 'mail.fields.username', placeholder: 'mailer@example.com' },
  [SMTP_KEYS.password]: { labelKey: 'mail.fields.password' },
  [SMTP_KEYS.encryption]: { labelKey: 'mail.fields.encryption' },
  [SMTP_KEYS.from]: { labelKey: 'mail.fields.from', placeholder: 'no-reply@example.com' },
  [SMTP_KEYS.fromName]: { labelKey: 'mail.fields.fromName', placeholder: 'qnop' },
};

/**
 * Curated presentation for the `smtp.encryption` enum: keys of a short label plus
 * the port hint operators actually need. The option set still comes from the API's
 * `allowedValues`, so a backend change flows through without touching the UI.
 */
export const ENCRYPTION_META: Record<string, { labelKey: AdminKey; hintKey: AdminKey }> = {
  none: { labelKey: 'mail.encryption.none.label', hintKey: 'mail.encryption.none.hint' },
  starttls: {
    labelKey: 'mail.encryption.starttls.label',
    hintKey: 'mail.encryption.starttls.hint',
  },
  tls: { labelKey: 'mail.encryption.tls.label', hintKey: 'mail.encryption.tls.hint' },
};

/** Fallback option order when the API does not publish `allowedValues`. */
export const ENCRYPTION_FALLBACK = ['none', 'starttls', 'tls'] as const;

/** The delivery status as data: a tone plus `admin` catalog keys for its label and detail. */
export interface SmtpStatus {
  tone: BadgeTone;
  labelKey: AdminKey;
  detailKey: AdminKey;
}

/**
 * Derives the at-a-glance delivery status from the current (possibly unsaved)
 * values, mirroring the backend gate: enabled AND a host means mail will be
 * attempted; anything else is a no-op the operator should know about.
 */
export function computeSmtpStatus(enabled: boolean, host: string): SmtpStatus {
  if (!enabled) {
    return {
      tone: 'neutral',
      labelKey: 'mail.status.disabled.label',
      detailKey: 'mail.status.disabled.detail',
    };
  }
  if (host.trim() === '') {
    return {
      tone: 'amber',
      labelKey: 'mail.status.incomplete.label',
      detailKey: 'mail.status.incomplete.detail',
    };
  }
  return {
    tone: 'green',
    labelKey: 'mail.status.ready.label',
    detailKey: 'mail.status.ready.detail',
  };
}
