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
package io.qnop.service.oidc;

import java.time.Instant;
import java.util.UUID;

/**
 * Web-safe projection of an OIDC provider for the admin API (issue #21). The JPA entity never
 * reaches the web layer (ADR-0004), and the client secret is <strong>never</strong> exposed — only
 * {@code hasClientSecret} signals whether one is configured.
 */
public record OidcProviderView(
    UUID id,
    String name,
    String providerType,
    boolean enabled,
    String clientId,
    boolean hasClientSecret,
    String issuerUri,
    String scope,
    String authorizationUri,
    String tokenUri,
    String userInfoUri,
    String jwkSetUri,
    String userNameAttribute,
    String emailAttribute,
    String displayNameAttribute,
    Instant createdAt,
    Instant updatedAt) {}
