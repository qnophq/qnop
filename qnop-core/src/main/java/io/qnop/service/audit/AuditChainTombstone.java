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

import io.qnop.repository.AuditChainPrunedRange;
import io.qnop.repository.AuditChainTombstoneRow;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A parsed {@code audit.chain.pruned} tombstone (issue #928, ADR-0043). Parsing never throws: a
 * payload that is not exactly what the prune trigger writes — an object with a non-empty {@code
 * documentIds} string array and a non-empty {@code ranges} array of {@code {fromSeq, toSeq,
 * prevHash, lastHash}} — yields {@code malformed = true} and no ranges, so the verifier reports it
 * instead of aborting.
 */
public record AuditChainTombstone(
    Long recordedSeq,
    List<AuditChainPrunedRange> ranges,
    List<String> documentIds,
    boolean malformed) {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  public AuditChainTombstone {
    ranges = List.copyOf(ranges);
    documentIds = List.copyOf(documentIds);
  }

  /** Parses one stored tombstone; never throws. */
  public static AuditChainTombstone parse(AuditChainTombstoneRow row) {
    Long seq = row.recordedSeq();
    try {
      JsonNode root = row.detail() == null ? null : MAPPER.readTree(row.detail());
      if (root == null || !root.isObject()) {
        return malformed(seq);
      }
      List<String> ids = documentIds(root.get("documentIds"));
      List<AuditChainPrunedRange> ranges = ranges(seq, root.get("ranges"));
      if (ids == null || ids.isEmpty() || ranges == null || ranges.isEmpty()) {
        return malformed(seq);
      }
      return new AuditChainTombstone(seq, ranges, ids, false);
    } catch (JacksonException e) {
      return malformed(seq);
    }
  }

  private static AuditChainTombstone malformed(Long seq) {
    return new AuditChainTombstone(seq, List.of(), List.of(), true);
  }

  private static List<String> documentIds(JsonNode node) {
    if (node == null || !node.isArray()) {
      return null;
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode id : node) {
      if (!id.isString()) {
        return null;
      }
      ids.add(id.stringValue());
    }
    return ids;
  }

  private static List<AuditChainPrunedRange> ranges(Long seq, JsonNode node) {
    if (node == null || !node.isArray()) {
      return null;
    }
    List<AuditChainPrunedRange> ranges = new ArrayList<>();
    for (JsonNode range : node) {
      JsonNode from = range.get("fromSeq");
      JsonNode to = range.get("toSeq");
      JsonNode prev = range.get("prevHash");
      JsonNode last = range.get("lastHash");
      boolean valid =
          range.isObject()
              && from != null
              && from.canConvertToLong()
              && from.isIntegralNumber()
              && to != null
              && to.canConvertToLong()
              && to.isIntegralNumber()
              && prev != null
              && prev.isString()
              && last != null
              && last.isString();
      if (!valid) {
        return null;
      }
      ranges.add(
          new AuditChainPrunedRange(
              seq, from.longValue(), to.longValue(), prev.stringValue(), last.stringValue()));
    }
    return ranges;
  }
}
