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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.security.SecretCipher.KeyMaterial;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;

/** The versioned at-rest cipher (issue #927): format, legacy decryption, rotation, integrity. */
class SecretCipherTest {

  private static final KeyMaterial OLD =
      new KeyMaterial("old-encryption-key-0123456789-abcdefgh", "0123456789abcdef0123456789abcdef");
  private static final KeyMaterial NEW =
      new KeyMaterial("new-encryption-key-9876543210-zyxwvuts", "fedcba9876543210fedcba9876543210");

  private static final String SECRET = "smtp-password-Ünïcode-✓";

  @Test
  void encryptsToVersionedKeyIdentifiedFormatAndRoundTrips() {
    SecretCipher cipher = new SecretCipher(NEW, null);

    String stored = cipher.encrypt(SECRET);

    assertThat(stored).startsWith("v2:" + cipher.currentKeyId() + ":");
    assertThat(cipher.currentKeyId()).matches("[0-9a-f]{8}");
    assertThat(stored).doesNotContain(SECRET);
    assertThat(cipher.decrypt(stored)).isEqualTo(SECRET);
    assertThat(cipher.needsReEncryption(stored)).isFalse();
  }

  @Test
  void usesAFreshNonceForEveryValue() {
    SecretCipher cipher = new SecretCipher(NEW, null);

    assertThat(cipher.encrypt(SECRET)).isNotEqualTo(cipher.encrypt(SECRET));
  }

  @Test
  void keyIdIsStablePerKeyAndDistinctAcrossKeys() {
    assertThat(new SecretCipher(NEW, null).currentKeyId())
        .isEqualTo(new SecretCipher(NEW, null).currentKeyId())
        .isNotEqualTo(new SecretCipher(OLD, null).currentKeyId());
  }

  @Test
  void decryptsLegacyFormatAndFlagsItForReEncryption() {
    String legacy = Encryptors.delux(NEW.key(), NEW.saltHex()).encrypt(SECRET);
    SecretCipher cipher = new SecretCipher(NEW, null);

    assertThat(cipher.decrypt(legacy)).isEqualTo(SECRET);
    assertThat(cipher.needsReEncryption(legacy)).isTrue();
  }

  @Test
  void decryptsLegacyFormatWrittenUnderThePreviousKey() {
    String legacy = Encryptors.delux(OLD.key(), OLD.saltHex()).encrypt(SECRET);

    assertThat(new SecretCipher(NEW, OLD).decrypt(legacy)).isEqualTo(SECRET);
  }

  @Test
  void rotationDecryptsWithThePreviousKeyAndReEncryptsUnderTheCurrentOne() {
    String underOld = new SecretCipher(OLD, null).encrypt(SECRET);
    SecretCipher rotating = new SecretCipher(NEW, OLD);

    assertThat(rotating.needsReEncryption(underOld)).isTrue();
    String reEncrypted = rotating.encrypt(rotating.decrypt(underOld));

    assertThat(rotating.needsReEncryption(reEncrypted)).isFalse();
    // Once the previous key is dropped, the re-encrypted value still reads.
    assertThat(new SecretCipher(NEW, null).decrypt(reEncrypted)).isEqualTo(SECRET);
  }

  @Test
  void refusesAValueWrittenUnderAKeyThatIsNoLongerConfigured() {
    String underOld = new SecretCipher(OLD, null).encrypt(SECRET);
    SecretCipher cipher = new SecretCipher(NEW, null);

    assertThatThrownBy(() -> cipher.decrypt(underOld))
        .isInstanceOf(SecretDecryptionException.class)
        .hasMessageContaining("neither the current nor the previous")
        .hasMessageNotContaining(SECRET);
  }

  @Test
  void refusesALegacyValueNoConfiguredKeyCanRead() {
    String legacy = Encryptors.delux(OLD.key(), OLD.saltHex()).encrypt(SECRET);

    assertThatThrownBy(() -> new SecretCipher(NEW, null).decrypt(legacy))
        .isInstanceOf(SecretDecryptionException.class)
        .hasMessageNotContaining(legacy);
  }

  @Test
  void detectsATamperedCiphertext() {
    SecretCipher cipher = new SecretCipher(NEW, null);
    String stored = cipher.encrypt(SECRET);
    int payloadStart = stored.lastIndexOf(':') + 1;
    byte[] payload = Base64.getUrlDecoder().decode(stored.substring(payloadStart));
    payload[payload.length - 1] ^= 0x01;
    String tampered =
        stored.substring(0, payloadStart)
            + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);

    assertThatThrownBy(() -> cipher.decrypt(tampered))
        .isInstanceOf(SecretDecryptionException.class)
        .hasMessageContaining("integrity");
  }

  @Test
  void detectsAValueRelabelledToAnotherConfiguredKey() {
    SecretCipher rotating = new SecretCipher(NEW, OLD);
    String underOld = new SecretCipher(OLD, null).encrypt(SECRET);
    String oldId = new SecretCipher(OLD, null).currentKeyId();
    String relabelled = underOld.replace(":" + oldId + ":", ":" + rotating.currentKeyId() + ":");

    assertThatThrownBy(() -> rotating.decrypt(relabelled))
        .isInstanceOf(SecretDecryptionException.class);
  }

  @Test
  void rejectsMalformedVersionedValues() {
    SecretCipher cipher = new SecretCipher(NEW, null);
    String id = cipher.currentKeyId();

    assertThatThrownBy(() -> cipher.decrypt("v2:no-separator"))
        .isInstanceOf(SecretDecryptionException.class);
    assertThatThrownBy(() -> cipher.decrypt("v2:" + id + ":not*base64"))
        .isInstanceOf(SecretDecryptionException.class);
    assertThatThrownBy(() -> cipher.decrypt("v2:" + id + ":AAAA"))
        .isInstanceOf(SecretDecryptionException.class);
  }

  @Test
  void blankValuesNeverNeedReEncryption() {
    SecretCipher cipher = new SecretCipher(NEW, null);

    assertThat(cipher.needsReEncryption(null)).isFalse();
    assertThat(cipher.needsReEncryption("")).isFalse();
  }

  @Test
  void anIdenticalPreviousKeyIsIgnored() {
    SecretCipher cipher = new SecretCipher(NEW, NEW);

    assertThat(cipher.decrypt(cipher.encrypt(SECRET))).isEqualTo(SECRET);
  }

  @Test
  void keyMaterialNeverPrintsTheKey() {
    assertThat(NEW.toString()).doesNotContain(NEW.key()).doesNotContain(NEW.saltHex());
  }
}
