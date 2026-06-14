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
package io.qnop.service.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication/crypto configuration, bound from {@code qnop.auth.*} (env {@code QNOP_AUTH_*}, per
 * ADR-0020). A minimal slice of the wider security foundation (issue #10) is brought forward here
 * so {@code oidc_provider.client_secret} can be encrypted at rest from the first identity migration
 * (issue #11). Validation that rejects blank/placeholder secrets lives in the consuming
 * configuration so the context fails fast on an insecure default.
 *
 * @param encryptionKey symmetric key material for {@code Encryptors.delux}; must be set explicitly
 * @param encryptionSalt hex-encoded salt for the key-derivation step; must be set explicitly
 */
@ConfigurationProperties(prefix = "qnop.auth")
public record QnopAuthProperties(String encryptionKey, String encryptionSalt) {}
