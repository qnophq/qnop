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

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF (RFC 5869) extract-and-expand key derivation over HMAC-SHA256. Pure, dependency-free, and
 * deterministic — verified against the RFC 5869 test vectors — so domain-separated keys can be
 * derived without a live Spring context (ADR-0004).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5869">RFC 5869</a>
 */
public final class Hkdf {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final int HASH_LEN = 32;

  private Hkdf() {}

  /**
   * HKDF-Extract: derives a fixed-length pseudorandom key from the input keying material.
   *
   * @param salt optional non-secret salt; an empty/{@code null} salt defaults to {@code HashLen}
   *     zero bytes per the RFC
   * @param ikm input keying material
   * @return the 32-byte pseudorandom key (PRK)
   */
  public static byte[] extract(byte[] salt, byte[] ikm) {
    byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
    return hmac(effectiveSalt, ikm);
  }

  /**
   * HKDF-Expand: expands a pseudorandom key into output keying material of the requested length,
   * bound to {@code info} for domain separation.
   *
   * @param prk a pseudorandom key (typically from {@link #extract})
   * @param info optional context/application-specific info for domain separation
   * @param length desired output length in bytes; at most {@code 255 * HashLen}
   * @return {@code length} bytes of output keying material (OKM)
   */
  public static byte[] expand(byte[] prk, byte[] info, int length) {
    if (length < 0 || length > 255 * HASH_LEN) {
      throw new IllegalArgumentException("length must be between 0 and " + (255 * HASH_LEN));
    }
    byte[] safeInfo = info == null ? new byte[0] : info;
    byte[] okm = new byte[length];
    byte[] block = new byte[0];
    int position = 0;
    for (int counter = 1; position < length; counter++) {
      byte[] input = new byte[block.length + safeInfo.length + 1];
      System.arraycopy(block, 0, input, 0, block.length);
      System.arraycopy(safeInfo, 0, input, block.length, safeInfo.length);
      input[input.length - 1] = (byte) counter;
      block = hmac(prk, input);
      int toCopy = Math.min(block.length, length - position);
      System.arraycopy(block, 0, okm, position, toCopy);
      position += toCopy;
    }
    return okm;
  }

  /**
   * Convenience: {@link #extract} followed by {@link #expand}.
   *
   * @param ikm input keying material
   * @param salt optional salt
   * @param info optional domain-separation context
   * @param length desired output length in bytes
   * @return {@code length} bytes of output keying material
   */
  public static byte[] deriveKey(byte[] ikm, byte[] salt, byte[] info, int length) {
    return expand(extract(salt, ikm), info, length);
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is required but unavailable", e);
    }
  }
}
