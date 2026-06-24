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

import type { UserRole, UserSource } from '../../../api/generated';
import { ToneBadge, type BadgeTone } from '../ToneBadge';

const ROLE_TONE: Record<UserRole, BadgeTone> = {
  ADMIN: 'blue',
  AUDITOR: 'amber',
  MEMBER: 'neutral',
};
const ROLE_LABEL: Record<UserRole, string> = {
  ADMIN: 'Admin',
  MEMBER: 'Member',
  AUDITOR: 'Auditor',
};

/** The user's global role as a coloured pill. */
export function UserRoleBadge({ role }: { role: UserRole }) {
  return <ToneBadge tone={ROLE_TONE[role]} label={ROLE_LABEL[role]} />;
}

/** Account state: active (green) or disabled (red). */
export function UserStatusBadge({ enabled }: { enabled: boolean }) {
  return enabled ? (
    <ToneBadge tone="green" label="Active" />
  ) : (
    <ToneBadge tone="red" label="Disabled" />
  );
}

/**
 * Where the account comes from: a local (INTERNAL) login or an external OIDC
 * provider. External accounts show the provider's name (e.g. "Keycloak") so the
 * operator sees *which* IdP at a glance; local accounts read "Local".
 */
export function UserSourceBadge({
  source,
  providerName,
}: {
  source: UserSource;
  providerName?: string;
}) {
  return source === 'EXTERNAL' ? (
    <ToneBadge tone="blue" label={providerName?.trim() || 'External'} />
  ) : (
    <ToneBadge tone="neutral" label="Local" />
  );
}
