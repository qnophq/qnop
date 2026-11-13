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

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.repository.AuditChainTombstoneRow;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AuditChainRedactionTest {

  private static final String ID = "0190a1b2-c3d4-7e5f-8a9b-0000000000aa";
  private static final String ROW_A = "0190a1b2-c3d4-7e5f-8a9b-00000000000a";
  private static final String ROW_B = "0190a1b2-c3d4-7e5f-8a9b-00000000000b";

  private static String sealedEntry(String id) {
    return "{\"id\": \"" + id + "\", \"seq\": 2, \"rowHash\": \"aa\", \"contentHash\": \"cc\"}";
  }

  private static String pendingEntry(String id) {
    return "{\"id\": \"" + id + "\", \"seq\": null, \"rowHash\": null, \"contentHash\": \"dd\"}";
  }

  private static String payload(String rows, Object pending) {
    return "{\"rows\": ["
        + rows
        + "], \"pendingRows\": "
        + pending
        + ", \"redactionId\": \""
        + ID
        + "\"}";
  }

  @Test
  @DisplayName("parses the payload the redaction function writes")
  void parsesFunctionPayload() {
    AuditChainRedaction redaction =
        AuditChainRedaction.parse(
            new AuditChainTombstoneRow(
                9L, payload(sealedEntry(ROW_A) + ", " + pendingEntry(ROW_B), 1)));

    assertThat(redaction.malformed()).isFalse();
    assertThat(redaction.recordedSeq()).isEqualTo(9L);
    assertThat(redaction.redactionId()).isEqualTo(UUID.fromString(ID));
    assertThat(redaction.rows())
        .containsEntry(UUID.fromString(ROW_A), new AuditChainRedaction.Listing(2L, "aa", "cc"))
        .containsEntry(UUID.fromString(ROW_B), new AuditChainRedaction.Listing(null, null, "dd"));
  }

  static List<String> malformed() {
    return Arrays.asList(
        null,
        "not json",
        "[]",
        "{}",
        payload("", 0),
        payload(sealedEntry(ROW_A), 1),
        payload(pendingEntry(ROW_B), 0),
        payload(sealedEntry(ROW_A), -1),
        payload(sealedEntry(ROW_A), "\"0\""),
        payload(sealedEntry(ROW_A) + ", " + sealedEntry(ROW_A), 0),
        payload("{\"id\": \"nope\", \"seq\": 2, \"rowHash\": \"a\", \"contentHash\": \"c\"}", 0),
        payload(
            "{\"id\": \"" + ROW_A + "\", \"seq\": 2, \"rowHash\": null, \"contentHash\": \"c\"}",
            0),
        payload("{\"id\": \"" + ROW_A + "\", \"seq\": 2, \"rowHash\": \"a\"}", 0),
        "{\"rows\": [" + sealedEntry(ROW_A) + "], \"pendingRows\": 0, \"redactionId\": \"x\"}");
  }

  @ParameterizedTest
  @MethodSource("malformed")
  @DisplayName("anything else is malformed, never an exception")
  void malformedPayloads(String detail) {
    AuditChainRedaction redaction =
        AuditChainRedaction.parse(new AuditChainTombstoneRow(3L, detail));

    assertThat(redaction.malformed()).isTrue();
    assertThat(redaction.rows()).isEmpty();
  }
}
