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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuditChainRedactionTest {

  private static final String ID = "0190a1b2-c3d4-7e5f-8a9b-0000000000aa";

  @Test
  @DisplayName("parses the payload the redaction function writes")
  void parsesFunctionPayload() {
    AuditChainRedaction redaction =
        AuditChainRedaction.parse(
            new AuditChainTombstoneRow(
                9L,
                "{\"rows\": [{\"seq\": 2, \"rowHash\": \"aa\"}, {\"seq\": 5, \"rowHash\": \"bb\"}],"
                    + " \"pendingRows\": 1, \"redactionId\": \""
                    + ID
                    + "\"}"));

    assertThat(redaction.malformed()).isFalse();
    assertThat(redaction.recordedSeq()).isEqualTo(9L);
    assertThat(redaction.redactionId()).isEqualTo(UUID.fromString(ID));
    assertThat(redaction.rows()).isEqualTo(Map.of(2L, "aa", 5L, "bb"));
    assertThat(redaction.pendingRows()).isEqualTo(1);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "not json",
        "[]",
        "{}",
        "{\"redactionId\": \"nope\", \"rows\": [], \"pendingRows\": 1}",
        "{\"redactionId\": \"" + ID + "\", \"rows\": [], \"pendingRows\": 0}",
        "{\"redactionId\": \"" + ID + "\", \"rows\": [], \"pendingRows\": -1}",
        "{\"redactionId\": \"" + ID + "\", \"rows\": {}, \"pendingRows\": 1}",
        "{\"redactionId\": \""
            + ID
            + "\", \"rows\": [{\"seq\": \"2\", \"rowHash\": \"a\"}],"
            + " \"pendingRows\": 0}",
        "{\"redactionId\": \""
            + ID
            + "\", \"rows\": [{\"seq\": 2, \"rowHash\": \"a\"},"
            + " {\"seq\": 2, \"rowHash\": \"b\"}], \"pendingRows\": 0}",
        "{\"redactionId\": \"" + ID + "\", \"rows\": [{\"seq\": 2, \"rowHash\": \"a\"}]}"
      })
  @DisplayName("anything else is malformed, never an exception")
  void malformedPayloads(String detail) {
    AuditChainRedaction redaction =
        AuditChainRedaction.parse(new AuditChainTombstoneRow(3L, detail));

    assertThat(redaction.malformed()).isTrue();
    assertThat(redaction.rows()).isEmpty();
  }
}
