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
package io.qnop.service.audit;

import io.qnop.repository.AuditChainRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The canonical form and hash of one audit-chain link (issue #928, ADR-0043) — an independent Java
 * re-implementation of {@code audit_event_canonical} / {@code audit_event_seal} in {@code
 * 0041-audit-chain.sql}. The verifier recomputes every row with this class, so a database function
 * quietly redefined to ignore a column cannot make tampering with that column pass.
 *
 * <p>Canonical form: format version {@code "1"}, id, scope, document id, event type, actor id,
 * detail (PostgreSQL's normalised jsonb text), created-at (epoch microseconds), each encoded as
 * {@code <UTF-8 byte length>:<text>} or {@code ~} for null, concatenated without separators. The
 * length prefix makes the encoding unambiguous: no choice of field values can make two different
 * rows encode alike. The link hash is {@code hex(sha256(utf8(prevHash + canonical)))}.
 */
public final class AuditChainHasher {

  /** The {@code prev_hash} of the first link. */
  public static final String GENESIS = "0".repeat(64);

  static final String FORMAT_VERSION = "1";

  private AuditChainHasher() {}

  /** The canonical encoding of a row's content (chain columns excluded). */
  public static String canonical(
      UUID id,
      String scope,
      UUID documentId,
      String eventType,
      UUID actorId,
      String detail,
      long createdAtMicros) {
    List<String> fields =
        Arrays.asList(
            FORMAT_VERSION,
            text(id),
            scope,
            text(documentId),
            eventType,
            text(actorId),
            detail,
            Long.toString(createdAtMicros));
    StringBuilder out = new StringBuilder();
    for (String field : fields) {
      if (field == null) {
        out.append('~');
      } else {
        out.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field);
      }
    }
    return out.toString();
  }

  /** The canonical encoding of a row read back from the chain. */
  public static String canonical(AuditChainRow row) {
    return canonical(
        row.id(),
        row.scope(),
        row.documentId(),
        row.eventType(),
        row.actorId(),
        row.detail(),
        row.createdAtMicros());
  }

  /** {@code hex(sha256(utf8(prevHash + canonical)))}, lower-case. */
  public static String linkHash(String prevHash, String canonical) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      byte[] digest = sha256.digest((prevHash + canonical).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // Every Java SE platform must ship SHA-256.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String text(UUID uuid) {
    return uuid == null ? null : uuid.toString();
  }
}
