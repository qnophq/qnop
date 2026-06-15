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
package io.qnop.service;

import java.time.Instant;
import java.util.UUID;

/**
 * A web-safe projection of a user for the admin API (issue #20), so the JPA entity never reaches
 * the web layer (ADR-0004). {@code source} is a plain string ({@code INTERNAL}/{@code EXTERNAL}) to
 * keep the entity enum out of the web layer too.
 */
public record UserView(
    UUID id,
    String username,
    String email,
    String displayName,
    boolean enabled,
    boolean superadmin,
    String source,
    Instant createdAt,
    Instant lastLoginAt) {}
