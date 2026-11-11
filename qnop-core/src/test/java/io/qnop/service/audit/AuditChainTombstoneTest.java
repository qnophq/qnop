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

import io.qnop.repository.AuditChainPrunedRange;
import io.qnop.repository.AuditChainTombstoneRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuditChainTombstoneTest {

  @Test
  @DisplayName("parses the payload the prune trigger writes")
  void parsesTriggerPayload() {
    AuditChainTombstone tombstone =
        AuditChainTombstone.parse(
            new AuditChainTombstoneRow(
                7L,
                "{\"ranges\": [{\"toSeq\": 3, \"fromSeq\": 2, \"lastHash\": \"bb\","
                    + " \"prevHash\": \"aa\"}], \"documentIds\": [\"d1\"]}"));

    assertThat(tombstone.malformed()).isFalse();
    assertThat(tombstone.documentIds()).containsExactly("d1");
    assertThat(tombstone.ranges()).containsExactly(new AuditChainPrunedRange(7L, 2, 3, "aa", "bb"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "not json",
        "[]",
        "\"text\"",
        "{}",
        "{\"documentIds\": [\"d1\"]}",
        "{\"documentIds\": [], \"ranges\": [{\"fromSeq\": 1, \"toSeq\": 1, \"prevHash\": \"a\","
            + " \"lastHash\": \"b\"}]}",
        "{\"documentIds\": [\"d1\"], \"ranges\": \"oops\"}",
        "{\"documentIds\": [\"d1\"], \"ranges\": []}",
        "{\"documentIds\": [\"d1\"], \"ranges\": [42]}",
        "{\"documentIds\": [1], \"ranges\": [{\"fromSeq\": 1, \"toSeq\": 1, \"prevHash\": \"a\","
            + " \"lastHash\": \"b\"}]}",
        "{\"documentIds\": [\"d1\"], \"ranges\": [{\"fromSeq\": 1.5, \"toSeq\": 2,"
            + " \"prevHash\": \"a\", \"lastHash\": \"b\"}]}",
        "{\"documentIds\": [\"d1\"], \"ranges\": [{\"fromSeq\": \"1\", \"toSeq\": 2,"
            + " \"prevHash\": \"a\", \"lastHash\": \"b\"}]}",
        "{\"documentIds\": [\"d1\"], \"ranges\": [{\"fromSeq\": 99999999999999999999999,"
            + " \"toSeq\": 2, \"prevHash\": \"a\", \"lastHash\": \"b\"}]}",
        "{\"documentIds\": [\"d1\"], \"ranges\": [{\"fromSeq\": 1, \"toSeq\": 2,"
            + " \"prevHash\": 5, \"lastHash\": \"b\"}]}"
      })
  @DisplayName("anything else is malformed, never an exception")
  void malformedPayloads(String detail) {
    AuditChainTombstone tombstone =
        AuditChainTombstone.parse(new AuditChainTombstoneRow(4L, detail));

    assertThat(tombstone.malformed()).isTrue();
    assertThat(tombstone.ranges()).isEmpty();
  }
}
