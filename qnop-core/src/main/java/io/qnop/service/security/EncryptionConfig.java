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
package io.qnop.service.security;

import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * Wires the application {@link TextEncryptor} used to encrypt secrets at rest (currently {@code
 * oidc_provider.client_secret}). Fails fast at startup when the configured key/salt are absent or
 * obviously insecure, so a misconfigured deployment never silently stores recoverable or weakly
 * protected secrets (issue #10 foundation, brought forward by issue #11).
 */
@Configuration
@EnableConfigurationProperties(QnopAuthProperties.class)
public class EncryptionConfig {

  private static final Set<String> INSECURE_KEYS =
      Set.of("", "change-me", "changeme", "changeit", "secret", "password", "default");

  @Bean
  TextEncryptor textEncryptor(QnopAuthProperties properties) {
    String key = properties.encryptionKey();
    String salt = properties.encryptionSalt();

    if (key == null || INSECURE_KEYS.contains(key.trim().toLowerCase())) {
      throw new IllegalStateException(
          "qnop.auth.encryption-key (env QNOP_AUTH_ENCRYPTION_KEY) is missing or set to an"
              + " insecure default; configure a strong secret.");
    }
    if (salt == null || salt.isBlank() || !isHex(salt)) {
      throw new IllegalStateException(
          "qnop.auth.encryption-salt (env QNOP_AUTH_ENCRYPTION_SALT) must be a non-empty"
              + " hex-encoded string.");
    }
    return Encryptors.delux(key, salt);
  }

  private static boolean isHex(String value) {
    if (value.length() % 2 != 0) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (Character.digit(value.charAt(i), 16) < 0) {
        return false;
      }
    }
    return true;
  }
}
