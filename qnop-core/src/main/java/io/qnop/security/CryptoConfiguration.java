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
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Symmetric and one-way cryptographic primitives shared across the auth subsystem (issue #10).
 *
 * <ul>
 *   <li>{@link PasswordEncoder} — BCrypt, for user password hashing (issue #20).
 *   <li>{@link TextEncryptor} — {@code Encryptors.delux} (AES-256), for secrets stored at rest such
 *       as OIDC client secrets (issue #21).
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

  @Bean
  TextEncryptor textEncryptor(QnopProperties properties) {
    QnopProperties.Auth auth = properties.auth();
    return Encryptors.delux(auth.encryptionKey(), auth.encryptionSalt());
  }
}
