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

import io.qnop.repository.AuditChainRow;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditChainHasherTest {

  private static final UUID ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
  private static final UUID DOC = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-000000000001");

  @Test
  @DisplayName("canonical form length-prefixes each field in a fixed order and marks nulls")
  void canonicalFormIsLengthPrefixed() {
    String canonical =
        AuditChainHasher.canonical(
            ID, "DOCUMENT", DOC, "annotation.created", null, "{\"a\": 1}", 1_731_300_000_123_456L);

    assertThat(canonical)
        .isEqualTo(
            "1:1"
                + "36:"
                + ID
                + "8:DOCUMENT"
                + "36:"
                + DOC
                + "18:annotation.created"
                + "~"
                + "8:{\"a\": 1}"
                + "16:1731300000123456");
  }

  @Test
  @DisplayName("lengths count UTF-8 bytes, matching PostgreSQL's octet_length")
  void lengthCountsUtf8Bytes() {
    String canonical =
        AuditChainHasher.canonical(ID, "SYSTEM", null, "x", null, "{\"t\": \"ü€\"}", 0L);

    // {"t": "ü€"} is 11 characters but 14 bytes (ü = 2, € = 3).
    assertThat(canonical).contains("14:{\"t\": \"ü€\"}");
  }

  @Test
  @DisplayName("shifting text between adjacent fields changes the encoding")
  void encodingIsUnambiguous() {
    String a = AuditChainHasher.canonical(ID, "SYSTEM", null, "ab", null, "c", 0L);
    String b = AuditChainHasher.canonical(ID, "SYSTEM", null, "a", null, "bc", 0L);
    String nullVsEmpty = AuditChainHasher.canonical(ID, "SYSTEM", null, "a", null, "", 0L);
    String nullDetail = AuditChainHasher.canonical(ID, "SYSTEM", null, "a", null, null, 0L);

    assertThat(a).isNotEqualTo(b);
    assertThat(nullVsEmpty).isNotEqualTo(nullDetail);
  }

  @Test
  @DisplayName("link hash is lower-case hex SHA-256 of prevHash followed by the canonical form")
  void linkHashIsSha256OfPrevAndCanonical() {
    // Reference value: printf '%s' "<64 zeros>abc" | sha256sum
    assertThat(AuditChainHasher.linkHash(AuditChainHasher.GENESIS, "abc"))
        .isEqualTo("b64374d04ef9c4f39fddb1e0d6be38a0130f6c057fc0f4ee467ea0e18bc758f1");
  }

  @Test
  @DisplayName("a row read back hashes the same as its fields")
  void rowOverloadMatchesFieldOverload() {
    AuditChainRow row =
        new AuditChainRow(
            1, AuditChainHasher.GENESIS, "x", ID, "DOCUMENT", DOC, "e", null, null, 42L);

    assertThat(AuditChainHasher.canonical(row))
        .isEqualTo(AuditChainHasher.canonical(ID, "DOCUMENT", DOC, "e", null, null, 42L));
  }
}
