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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Transparently encrypts string columns at rest (currently {@code oidc_provider.client_secret}).
 *
 * <p>Registered as a Spring bean so Hibernate resolves it through Spring's bean container and
 * injects the application {@link TextEncryptor} (derived from {@code qnop.auth.encryption-key}; see
 * the security configuration). Encryption is non-deterministic, so encrypted columns must never be
 * used as query predicates or unique keys.
 *
 * <p><b>Unreadable values (issue #927).</b> A value no configured key can decrypt — the key was
 * rotated without keeping the old one as previous, or the value was altered — reads as {@code null}
 * (no secret) with a loud error, rather than failing every load of the owning entity. The stored
 * ciphertext is not touched: entities using this converter are {@code @DynamicUpdate}, so an
 * unrelated edit never writes the {@code null} back, and restoring the right key recovers the
 * value. The startup re-encryption pass reports such values in the SYSTEM audit trail.
 */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

  private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);

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
    try {
      return encryptor.decrypt(dbData);
    } catch (IllegalStateException e) {
      // The cipher signals an undecryptable value with SecretDecryptionException, an
      // IllegalStateException (the entity layer may not depend on io.qnop.security); a broken
      // crypto provider is a different type and still propagates. Never log the value.
      log.error(
          "An encrypted column could not be decrypted with any configured encryption key and is"
              + " treated as unset: {}",
          e.getMessage());
      return null;
    }
  }
}
