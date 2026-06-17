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
package io.qnop.service;

import io.qnop.security.JwtKeyService;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Computes the lookup hash stored for an opaque refresh token (issue #17). Only {@code
 * HMAC-SHA256(plaintext)} is persisted (column {@code refresh_token.token_lookup_hash}); the
 * plaintext lives solely in the client's cookie, so a database leak exposes no usable tokens. The
 * HMAC key is HKDF-derived from {@code qnop.auth.jwt-secret} (ADR-0022), domain-separated from the
 * JWT signing key.
 */
@Component
public class RefreshTokenHasher {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final SecretKey key;

  public RefreshTokenHasher(JwtKeyService jwtKeyService) {
    this.key = jwtKeyService.deriveKey("refresh-token-lookup");
  }

  /** Returns the 64-character lowercase-hex HMAC-SHA256 of the token. */
  public String hash(String plaintext) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(key);
      byte[] digest = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Unable to compute refresh-token lookup hash", e);
    }
  }
}
