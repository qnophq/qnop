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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Symmetric and one-way cryptographic primitives shared across the auth subsystem (issue #10).
 *
 * <ul>
 *   <li>{@link PasswordEncoder} — BCrypt, for user password hashing (issue #20).
 *   <li>{@link TextEncryptor} — the {@link SecretCipher} (versioned, key-identified AES-256-GCM
 *       with an HKDF-derived key), for secrets stored at rest such as OIDC client secrets (issue
 *       #21) and the SMTP password; it supports key rotation (issue #927).
 * </ul>
 *
 * <p>These are beans rather than the web filter chain, so they live in the framework-light {@code
 * io.qnop.security} layer and are injectable by the service layer.
 */
@Configuration
public class CryptoConfiguration {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * The cipher for secrets at rest. Exposed as {@link SecretCipher} (a {@link TextEncryptor}) so
   * the re-encryption pass can ask which stored values still need rewriting.
   *
   * @throws IllegalStateException if a previous (rotation) key is configured but weak
   */
  @Bean
  SecretCipher textEncryptor(QnopProperties properties) {
    QnopProperties.Auth auth = properties.auth();
    SecretCipher.KeyMaterial current =
        new SecretCipher.KeyMaterial(auth.encryptionKey(), auth.encryptionSalt());
    SecretCipher.KeyMaterial previous = null;
    if (auth.hasPreviousEncryptionKey()) {
      if (!SecretValidator.isStrong(auth.previousEncryptionKey())) {
        throw new IllegalStateException(
            "qnop.auth.previous-encryption-key must be blank or a strong, non-default secret of at"
                + " least "
                + SecretValidator.MIN_LENGTH
                + " characters");
      }
      String salt =
          auth.previousEncryptionSalt().isEmpty()
              ? auth.encryptionSalt()
              : auth.previousEncryptionSalt();
      previous = new SecretCipher.KeyMaterial(auth.previousEncryptionKey(), salt);
    }
    return new SecretCipher(current, previous);
  }
}
