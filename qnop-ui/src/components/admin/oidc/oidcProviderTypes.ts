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
import { OidcProviderTypeDto } from '../../../api/generated';

/**
 * The provider-type enum in form/select order, each with the `admin` catalog key of its
 * human-readable label (issue #836) — translated at render. Vendor names stay as they are in
 * every language, but live in the catalog too so the map stays uniform.
 */
export const PROVIDER_TYPES: { value: OidcProviderTypeDto; labelKey: ParseKeys<'admin'> }[] = [
  { value: OidcProviderTypeDto.Oidc, labelKey: 'oidc.types.OIDC' },
  { value: OidcProviderTypeDto.Google, labelKey: 'oidc.types.GOOGLE' },
  { value: OidcProviderTypeDto.Github, labelKey: 'oidc.types.GITHUB' },
  { value: OidcProviderTypeDto.Facebook, labelKey: 'oidc.types.FACEBOOK' },
  { value: OidcProviderTypeDto.Oauth2, labelKey: 'oidc.types.OAUTH2' },
];

const LABEL_KEYS: Record<string, ParseKeys<'admin'>> = Object.fromEntries(
  PROVIDER_TYPES.map((type) => [type.value, type.labelKey]),
);

/** The catalog key of a provider type's label, or `undefined` for a type the UI does not know. */
export function providerTypeLabelKey(type: OidcProviderTypeDto): ParseKeys<'admin'> | undefined {
  return LABEL_KEYS[type];
}

/** Whether the type resolves its endpoints from an issuer's discovery document. */
export function supportsDiscovery(type: OidcProviderTypeDto): boolean {
  return type === OidcProviderTypeDto.Oidc || type === OidcProviderTypeDto.Google;
}
