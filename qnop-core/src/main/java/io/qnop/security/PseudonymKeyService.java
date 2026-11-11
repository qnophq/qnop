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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Keyed, per-review sort keys for anonymous-review pseudonyms (issue #914, ADR-0038 amendment).
 *
 * <p>The order in which a review's participants receive their "Participant N" labels must not be a
 * function of public data (user ids are time-ordered and visible in the directory). This service
 * returns {@code HMAC-SHA256(k, documentId || principalId)}, where {@code k} is derived from the
 * configured {@code qnop.auth.jwt-secret} via HKDF under its own purpose label (ADR-0022) — so it
 * is independent of every token-signing key. Without the server secret the order is unpredictable,
 * and binding the document id makes it differ per review.
 */
@Service
public class PseudonymKeyService {

  private static final String ALGORITHM = "HmacSHA256";
  private static final int KEY_LENGTH_BYTES = 32;

  /** Fixed salt; domain separation is carried by {@link #PURPOSE}. */
  private static final byte[] APPLICATION_SALT =
      "qnop:review:hkdf:v1".getBytes(StandardCharsets.UTF_8);

  private static final byte[] PURPOSE =
      "qnop:review:pseudonym-order".getBytes(StandardCharsets.UTF_8);

  private final SecretKeySpec key;

  @Autowired
  public PseudonymKeyService(QnopProperties properties) {
    this(properties.auth().jwtSecret());
  }

  /** Derives the key from an explicit secret (unit tests). */
  public static PseudonymKeyService fromSecret(String secret) {
    return new PseudonymKeyService(secret);
  }

  private PseudonymKeyService(String secret) {
    Objects.requireNonNull(secret, "secret");
    byte[] keyBytes =
        Hkdf.deriveKey(
            secret.getBytes(StandardCharsets.UTF_8), APPLICATION_SALT, PURPOSE, KEY_LENGTH_BYTES);
    this.key = new SecretKeySpec(keyBytes, ALGORITHM);
  }

  /** The 32-byte keyed sort key of {@code principalId} within {@code documentId}'s review. */
  public byte[] sortKey(UUID documentId, UUID principalId) {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(principalId, "principalId");
    ByteBuffer input =
        ByteBuffer.allocate(32)
            .putLong(documentId.getMostSignificantBits())
            .putLong(documentId.getLeastSignificantBits())
            .putLong(principalId.getMostSignificantBits())
            .putLong(principalId.getLeastSignificantBits());
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(input.array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is required but unavailable", e);
    }
  }
}
