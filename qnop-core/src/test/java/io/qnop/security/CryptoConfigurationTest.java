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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Verifies the crypto beans without a Spring context — the @Configuration is plain Java. */
class CryptoConfigurationTest {

  private final CryptoConfiguration config = new CryptoConfiguration();

  private static QnopProperties properties() {
    return new QnopProperties(
        new QnopProperties.Auth(
            "crypto-test-jwt-secret-0123456789-abc",
            "crypto-test-encryption-key-0123456789",
            "0123456789abcdef0123456789abcdef",
            null,
            null,
            null,
            null,
            null),
        new QnopProperties.Cors(List.of()));
  }

  @Test
  void passwordEncoderHashesAndMatches() {
    PasswordEncoder encoder = config.passwordEncoder();

    String hash = encoder.encode("s3cret-password");

    assertNotEquals("s3cret-password", hash);
    assertTrue(encoder.matches("s3cret-password", hash));
    assertFalse(encoder.matches("wrong-password", hash));
  }

  private static QnopProperties rotatingProperties(String previousKey, String previousSalt) {
    return new QnopProperties(
        new QnopProperties.Auth(
            "crypto-test-jwt-secret-0123456789-abc",
            "crypto-test-encryption-key-0123456789",
            "0123456789abcdef0123456789abcdef",
            null,
            null,
            null,
            null,
            null,
            previousKey,
            previousSalt,
            null),
        new QnopProperties.Cors(List.of()));
  }

  @Test
  void textEncryptorAcceptsValuesWrittenUnderThePreviousKey() {
    String previousKey = "crypto-test-previous-key-9876543210";
    String previousSalt = "fedcba9876543210fedcba9876543210";
    String written =
        new SecretCipher(new SecretCipher.KeyMaterial(previousKey, previousSalt), null)
            .encrypt("rotated-secret");

    SecretCipher rotating = config.textEncryptor(rotatingProperties(previousKey, previousSalt));

    assertEquals("rotated-secret", rotating.decrypt(written));
    assertTrue(rotating.needsReEncryption(written));
  }

  @Test
  void previousKeyWithoutItsOwnSaltSharesTheCurrentSalt() {
    String previousKey = "crypto-test-previous-key-9876543210";
    String written =
        new SecretCipher(
                new SecretCipher.KeyMaterial(previousKey, "0123456789abcdef0123456789abcdef"), null)
            .encrypt("rotated-secret");

    assertEquals(
        "rotated-secret",
        config.textEncryptor(rotatingProperties(previousKey, "")).decrypt(written));
  }

  @Test
  void weakPreviousKeyIsRefused() {
    QnopProperties weak = rotatingProperties("CHANGE_ME", null);

    assertThrows(IllegalStateException.class, () -> config.textEncryptor(weak));
  }

  @Test
  void textEncryptorRoundTrips() {
    TextEncryptor encryptor = config.textEncryptor(properties());

    String plaintext = "sensitive-oidc-client-secret";
    String ciphertext = encryptor.encrypt(plaintext);

    assertNotEquals(plaintext, ciphertext);
    assertEquals(plaintext, encryptor.decrypt(ciphertext));
  }
}
