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
package io.qnop.entity;

/**
 * Kind of external identity provider an {@link OidcProvider} represents. {@code OIDC} and {@code
 * OAUTH2} are the generic, fully configurable variants; the named entries carry well-known vendor
 * defaults. Persisted as a string and guarded by a Postgres {@code CHECK} constraint (ADR-0020).
 */
public enum OidcProviderType {
  OIDC,
  GITHUB,
  GOOGLE,
  FACEBOOK,
  OAUTH2
}
