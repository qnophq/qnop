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
package io.qnop.repository;

import java.util.UUID;

/**
 * One sealed {@code audit_event} row as the chain verifier reads it (issue #928, ADR-0043): its
 * chain position and stored hashes, plus the exact content the hash covers. {@code detail} is the
 * PostgreSQL-normalised jsonb text and {@code createdAtMicros} the timestamp in epoch microseconds
 * — the same representations the sealing trigger hashed.
 */
public record AuditChainRow(
    long seq,
    String prevHash,
    String rowHash,
    UUID id,
    String scope,
    UUID documentId,
    String eventType,
    UUID actorId,
    String detail,
    long createdAtMicros) {}
