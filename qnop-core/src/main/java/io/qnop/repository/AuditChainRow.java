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
 *
 * <p>{@code redactionId}, {@code actorPseudonym} and {@code redactedAtMicros} are set when a user
 * erasure pseudonymised the row (issue #952): its content then no longer reproduces its original
 * hash, and the verifier checks it against the content hash the {@code audit.chain.redacted} event
 * of that redaction recorded instead. All three are null for an unredacted row ({@code
 * actorPseudonym} also for a redacted row whose actor was not the erased user).
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
    long createdAtMicros,
    UUID redactionId,
    String actorPseudonym,
    Long redactedAtMicros) {

  /** An unredacted row. */
  public AuditChainRow(
      long seq,
      String prevHash,
      String rowHash,
      UUID id,
      String scope,
      UUID documentId,
      String eventType,
      UUID actorId,
      String detail,
      long createdAtMicros) {
    this(
        seq,
        prevHash,
        rowHash,
        id,
        scope,
        documentId,
        eventType,
        actorId,
        detail,
        createdAtMicros,
        null,
        null,
        null);
  }
}
