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
package io.qnop.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Verifies the HKDF implementation against the RFC 5869 test vectors and its derivation contract.
 */
class HkdfTest {

  private static final HexFormat HEX = HexFormat.of();

  @Test
  void matchesRfc5869BasicSha256Vector() {
    // RFC 5869, Appendix A.1 (Test Case 1, SHA-256).
    byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
    byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
    byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");

    byte[] prk = Hkdf.extract(salt, ikm);
    assertEquals(
        "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", HEX.formatHex(prk));

    byte[] okm = Hkdf.deriveKey(ikm, salt, info, 42);
    assertEquals(
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        HEX.formatHex(okm));
  }

  @Test
  void deriveKeyIsDeterministicAndDomainSeparated() {
    byte[] ikm = "input-keying-material".getBytes(StandardCharsets.UTF_8);
    byte[] infoA = "purpose-a".getBytes(StandardCharsets.UTF_8);
    byte[] infoB = "purpose-b".getBytes(StandardCharsets.UTF_8);

    byte[] first = Hkdf.deriveKey(ikm, null, infoA, 32);
    byte[] again = Hkdf.deriveKey(ikm, null, infoA, 32);
    byte[] other = Hkdf.deriveKey(ikm, null, infoB, 32);

    assertArrayEquals(first, again);
    assertEquals(32, first.length);
    assertFalse(Arrays.equals(first, other));
  }
}
