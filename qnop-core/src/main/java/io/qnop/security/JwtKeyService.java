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

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Derives domain-separated HMAC-SHA256 keys from the configured {@code qnop.auth.jwt-secret} using
 * HKDF (ADR-0021). Each {@code purpose} yields an independent key, so compromising — or rotating —
 * one purpose's key does not affect the others. Actual token issuance and verification land in
 * issue #17; this service is the shared key-derivation foundation.
 */
@Service
public class JwtKeyService {

  private static final String KEY_ALGORITHM = "HmacSHA256";
  private static final int KEY_LENGTH_BYTES = 32;

  /** Fixed application salt; domain separation is carried by the per-purpose {@code info} label. */
  private static final byte[] APPLICATION_SALT =
      "qnop:auth:hkdf:v1".getBytes(StandardCharsets.UTF_8);

  private final byte[] inputKeyingMaterial;

  public JwtKeyService(QnopProperties properties) {
    this.inputKeyingMaterial = properties.auth().jwtSecret().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Derives the signing key for the given purpose (e.g. {@code "access-token"}, {@code
   * "refresh-token"}).
   *
   * @param purpose a stable, non-secret label identifying the key's use
   * @return a 256-bit {@link SecretKey} unique to that purpose
   */
  public SecretKey deriveKey(String purpose) {
    Objects.requireNonNull(purpose, "purpose");
    byte[] info = ("qnop:jwt:" + purpose).getBytes(StandardCharsets.UTF_8);
    byte[] keyBytes = Hkdf.deriveKey(inputKeyingMaterial, APPLICATION_SALT, info, KEY_LENGTH_BYTES);
    return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
  }
}
