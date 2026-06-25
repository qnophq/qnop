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

import { OidcProviderTypeDto } from '../../../api/generated';

/** Human-readable labels for the provider-type enum, in form/select order. */
export const PROVIDER_TYPES: { value: OidcProviderTypeDto; label: string }[] = [
  { value: OidcProviderTypeDto.Oidc, label: 'Generic OIDC' },
  { value: OidcProviderTypeDto.Google, label: 'Google' },
  { value: OidcProviderTypeDto.Github, label: 'GitHub' },
  { value: OidcProviderTypeDto.Facebook, label: 'Facebook' },
  { value: OidcProviderTypeDto.Oauth2, label: 'Generic OAuth2' },
];

const LABELS: Record<string, string> = Object.fromEntries(
  PROVIDER_TYPES.map((type) => [type.value, type.label]),
);

export function providerTypeLabel(type: OidcProviderTypeDto): string {
  return LABELS[type] ?? type;
}

/** Whether the type resolves its endpoints from an issuer's discovery document. */
export function supportsDiscovery(type: OidcProviderTypeDto): boolean {
  return type === OidcProviderTypeDto.Oidc || type === OidcProviderTypeDto.Google;
}
