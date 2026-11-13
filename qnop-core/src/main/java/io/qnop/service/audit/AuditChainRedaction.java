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
 * leaves of the audit rows it pseudonymised. {@code rows} maps each redacted chain position to the
 * {@code row_hash} it carried when it was redacted; {@code pendingRows} counts rows that were
 * redacted before they were sealed (and were then sealed over their redacted content).
 *
 * <p>Parsing never throws: a payload that is not exactly what {@code audit_event_redact_user}
 * writes — an object with a uuid {@code redactionId}, a {@code rows} array of distinct {@code {seq,
 * rowHash}} and a non-negative integral {@code pendingRows}, listing at least one row — yields
 * {@code malformed = true}, which the verifier reports.
 */
public record AuditChainRedaction(
    Long recordedSeq,
    UUID redactionId,
    Map<Long, String> rows,
    long pendingRows,
    boolean malformed) {

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
      Map<Long, String> rows = rows(root.get("rows"));
      long pending = count(root.get("pendingRows"));
      if (redactionId == null || rows == null || pending < 0 || (rows.isEmpty() && pending == 0)) {
        return malformed(seq);
      }
      return new AuditChainRedaction(seq, redactionId, rows, pending, false);
    } catch (JacksonException e) {
      return malformed(seq);
    }
  }

  private static AuditChainRedaction malformed(Long seq) {
    return new AuditChainRedaction(seq, null, Map.of(), 0, true);
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

  /** The listed positions, or null when the array or any entry is malformed or repeated. */
  private static Map<Long, String> rows(JsonNode node) {
    if (node == null || !node.isArray()) {
      return null;
    }
    Map<Long, String> rows = new HashMap<>();
    for (JsonNode entry : node) {
      JsonNode seq = entry.get("seq");
      JsonNode hash = entry.get("rowHash");
      boolean valid =
          entry.isObject()
              && seq != null
              && seq.isIntegralNumber()
              && seq.canConvertToLong()
              && hash != null
              && hash.isString();
      if (!valid || rows.put(seq.longValue(), hash.stringValue()) != null) {
        return null;
      }
    }
    return rows;
  }
}
