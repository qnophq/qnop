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

import type { BadgeTone } from '../../ToneBadge';

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

/** Human labels and placeholders for the SMTP fields (the API description is the helper text). */
export const SMTP_FIELD: Record<string, { label: string; placeholder?: string }> = {
  [SMTP_KEYS.host]: { label: 'Host', placeholder: 'smtp.example.com' },
  [SMTP_KEYS.port]: { label: 'Port', placeholder: '587' },
  [SMTP_KEYS.username]: { label: 'Username', placeholder: 'mailer@example.com' },
  [SMTP_KEYS.password]: { label: 'Password' },
  [SMTP_KEYS.encryption]: { label: 'Encryption' },
  [SMTP_KEYS.from]: { label: 'From address', placeholder: 'no-reply@example.com' },
  [SMTP_KEYS.fromName]: { label: 'From name', placeholder: 'qnop' },
};

/**
 * Curated presentation for the `smtp.encryption` enum: a short label plus the
 * port hint operators actually need. The option set still comes from the API's
 * `allowedValues`, so a backend change flows through without touching the UI.
 */
export const ENCRYPTION_META: Record<string, { label: string; hint: string }> = {
  none: { label: 'None', hint: 'Plain SMTP — no transport encryption' },
  starttls: { label: 'STARTTLS', hint: 'Required upgrade · typically port 587' },
  tls: { label: 'Implicit TLS', hint: 'SSL from connect · typically port 465' },
};

/** Fallback option order when the API does not publish `allowedValues`. */
export const ENCRYPTION_FALLBACK = ['none', 'starttls', 'tls'] as const;

export interface SmtpStatus {
  tone: BadgeTone;
  label: string;
  detail: string;
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
      label: 'Disabled',
      detail: 'Outgoing mail is turned off. Messages are skipped until you enable it.',
    };
  }
  if (host.trim() === '') {
    return {
      tone: 'amber',
      label: 'Incomplete',
      detail: 'Enabled, but no SMTP host is set yet — add a host to start sending.',
    };
  }
  return {
    tone: 'green',
    label: 'Ready',
    detail: 'Outgoing mail is enabled and a host is configured.',
  };
}
