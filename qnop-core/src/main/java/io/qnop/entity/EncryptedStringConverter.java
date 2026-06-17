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
package io.qnop.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Transparently encrypts string columns at rest (currently {@code oidc_provider.client_secret}).
 *
 * <p>Registered as a Spring bean so Hibernate resolves it through Spring's bean container and
 * injects the application {@link TextEncryptor} (derived from {@code qnop.auth.encryption-key}; see
 * the security configuration). Encryption is non-deterministic, so encrypted columns must never be
 * used as query predicates or unique keys.
 */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

  private final TextEncryptor encryptor;

  public EncryptedStringConverter(TextEncryptor encryptor) {
    this.encryptor = encryptor;
  }

  @Override
  public String convertToDatabaseColumn(String attribute) {
    // Keep null and empty distinct and unencrypted; only real secrets are encrypted.
    if (attribute == null || attribute.isEmpty()) {
      return attribute;
    }
    return encryptor.encrypt(attribute);
  }

  @Override
  public String convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isEmpty()) {
      return dbData;
    }
    return encryptor.decrypt(dbData);
  }
}
