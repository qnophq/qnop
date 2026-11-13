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

import io.qnop.repository.AuditChainTombstoneRow;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A parsed {@code audit.chain.redacted} event (issue #952, ADR-0043): the record a user erasure
 * leaves of the audit rows it pseudonymised, keyed by audit row id. Each {@link Listing} holds the
 * row's chain position and original {@code row_hash} — both null for a row that was not sealed yet
 * when it was redacted — and {@code contentHash}, the hash of its redacted content ({@link
 * AuditChainHasher#contentHash}).
 *
 * <p>Parsing never throws: a payload that is not exactly what {@code audit_event_redact_user}
 * writes — an object with a uuid {@code redactionId}, a non-empty {@code rows} array of distinct
 * {@code {id, seq, rowHash, contentHash}} (seq and rowHash both set or both null) and a {@code
 * pendingRows} equal to the number of unsealed entries — yields {@code malformed = true}.
 */
public record AuditChainRedaction(
    Long recordedSeq, UUID redactionId, Map<UUID, Listing> rows, boolean malformed) {

  /** One redacted row as the event lists it. */
  public record Listing(Long seq, String rowHash, String contentHash) {

    /** Whether the row had not been sealed yet when it was redacted. */
    public boolean pending() {
      return seq == null;
    }
  }

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  public AuditChainRedaction {
    rows = Map.copyOf(rows);
  }

  /** Parses one stored redaction event ({@code recordedSeq}, raw detail); never throws. */
  public static AuditChainRedaction parse(AuditChainTombstoneRow row) {
    Long seq = row.recordedSeq();
    try {
      JsonNode root = row.detail() == null ? null : MAPPER.readTree(row.detail());
      if (root == null || !root.isObject()) {
        return malformed(seq);
      }
      UUID redactionId = uuid(root.get("redactionId"));
      Map<UUID, Listing> rows = rows(root.get("rows"));
      long pending = count(root.get("pendingRows"));
      if (redactionId == null
          || rows == null
          || rows.isEmpty()
          || pending != rows.values().stream().filter(Listing::pending).count()) {
        return malformed(seq);
      }
      return new AuditChainRedaction(seq, redactionId, rows, false);
    } catch (JacksonException e) {
      return malformed(seq);
    }
  }

  private static AuditChainRedaction malformed(Long seq) {
    return new AuditChainRedaction(seq, null, Map.of(), true);
  }

  private static UUID uuid(JsonNode node) {
    if (node == null || !node.isString()) {
      return null;
    }
    try {
      return UUID.fromString(node.stringValue());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** A non-negative integral count, or -1 when absent or malformed. */
  private static long count(JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      return -1;
    }
    return node.longValue() < 0 ? -1 : node.longValue();
  }

  /** The listed rows, or null when the array or any entry is malformed or repeated. */
  private static Map<UUID, Listing> rows(JsonNode node) {
    if (node == null || !node.isArray()) {
      return null;
    }
    Map<UUID, Listing> rows = new HashMap<>();
    for (JsonNode entry : node) {
      Listing listing = listing(entry);
      UUID id = entry.isObject() ? uuid(entry.get("id")) : null;
      if (listing == null || id == null || rows.put(id, listing) != null) {
        return null;
      }
    }
    return rows;
  }

  private static Listing listing(JsonNode entry) {
    if (!entry.isObject()) {
      return null;
    }
    JsonNode seq = entry.get("seq");
    JsonNode rowHash = entry.get("rowHash");
    JsonNode contentHash = entry.get("contentHash");
    if (contentHash == null || !contentHash.isString()) {
      return null;
    }
    boolean sealed =
        seq != null
            && seq.isIntegralNumber()
            && seq.canConvertToLong()
            && rowHash != null
            && rowHash.isString();
    boolean pending = (seq == null || seq.isNull()) && (rowHash == null || rowHash.isNull());
    if (sealed) {
      return new Listing(seq.longValue(), rowHash.stringValue(), contentHash.stringValue());
    }
    return pending ? new Listing(null, null, contentHash.stringValue()) : null;
  }
}
