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
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * Versioned, key-identified encryption for secrets at rest (issue #927, ADR-0022 amendment).
 *
 * <p><b>Format.</b> {@code v2:<keyId>:<base64url(nonce || ciphertext || tag)>} — AES-256-GCM with a
 * fresh random 96-bit nonce per value and a 128-bit tag. The AES key is derived from the configured
 * encryption key with HKDF-SHA256 (the configured hex salt as HKDF salt, a purpose label of its own
 * as {@code info}). The {@code v2:<keyId>} prefix is bound into the tag as associated data, so a
 * value cannot be relabelled to another version or key.
 *
 * <p><b>Key id.</b> An 8-hex-character fingerprint, itself HKDF-derived under a separate purpose
 * label: it identifies which configured key wrote a value without revealing anything usable about
 * the key. That is what makes rotation possible — a value names the key it needs.
 *
 * <p><b>Legacy.</b> Values written before #927 are the hex output of Spring's {@code
 * Encryptors.delux} (PBKDF2-HMAC-SHA1, 1024 iterations). They carry no prefix, so they are
 * recognised by its absence and decrypted with the legacy scheme under each configured key in turn.
 * They are never written any more.
 *
 * <p><b>Rotation.</b> Encryption always uses the current key. Decryption also accepts the optional
 * previous key, so an operator can configure a new key, keep the old one as previous, let {@link
 * #needsReEncryption} drive a re-encryption pass, and then drop the previous key.
 *
 * <p>Plain, Spring-context-free code (ADR-0004); thread-safe.
 */
public final class SecretCipher implements TextEncryptor {

  /** The version tag of the current format. */
  static final String VERSION = "v2";

  private static final String PREFIX = VERSION + ":";
  private static final char SEPARATOR = ':';

  /** HKDF {@code info} for the AES-256-GCM data key — unique among the HKDF purpose labels. */
  private static final byte[] DATA_KEY_PURPOSE =
      "qnop:secrets-at-rest:aes-256-gcm:v2".getBytes(StandardCharsets.UTF_8);

  /** HKDF {@code info} for the public key fingerprint — independent of the data key. */
  private static final byte[] KEY_ID_PURPOSE =
      "qnop:secrets-at-rest:key-id:v2".getBytes(StandardCharsets.UTF_8);

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int KEY_LENGTH_BYTES = 32;
  private static final int NONCE_LENGTH_BYTES = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int KEY_ID_BYTES = 4;

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final SecureRandom random = new SecureRandom();
  private final KeyEntry current;

  /** Every key that may decrypt, current first, keyed by key id. */
  private final Map<String, KeyEntry> decryptionKeys;

  /**
   * @param current the key all new values are encrypted with
   * @param previous an optional older key accepted for decryption only, or {@code null}
   */
  public SecretCipher(KeyMaterial current, KeyMaterial previous) {
    this.current = KeyEntry.of(Objects.requireNonNull(current, "current key"));
    Map<String, KeyEntry> keys = new LinkedHashMap<>();
    keys.put(this.current.id(), this.current);
    if (previous != null && !previous.equals(current)) {
      KeyEntry old = KeyEntry.of(previous);
      if (keys.containsKey(old.id())) {
        throw new IllegalStateException(
            "The current and previous encryption keys have the same key id; configure distinct"
                + " keys.");
      }
      keys.put(old.id(), old);
    }
    this.decryptionKeys = Collections.unmodifiableMap(keys);
  }

  /** The id of the key new values are written with. */
  public String currentKeyId() {
    return current.id();
  }

  @Override
  public String encrypt(String plaintext) {
    Objects.requireNonNull(plaintext, "plaintext");
    byte[] nonce = new byte[NONCE_LENGTH_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(
          Cipher.ENCRYPT_MODE, current.dataKey(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(header(current.id()));
      byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] payload = new byte[nonce.length + sealed.length];
      System.arraycopy(nonce, 0, payload, 0, nonce.length);
      System.arraycopy(sealed, 0, payload, nonce.length, sealed.length);
      return PREFIX + current.id() + SEPARATOR + ENCODER.encodeToString(payload);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-256-GCM is required but unavailable", e);
    }
  }

  /**
   * Decrypts a stored value in the current format (under any configured key) or the legacy format.
   *
   * @throws SecretDecryptionException if no configured key can decrypt the value or it was altered
   */
  @Override
  public String decrypt(String stored) {
    Objects.requireNonNull(stored, "stored");
    return stored.startsWith(PREFIX) ? decryptVersioned(stored) : decryptLegacy(stored);
  }

  /**
   * Whether a stored value should be rewritten: it is in the legacy format, or it was written under
   * a key other than the current one. A pure format check — it does not decrypt.
   */
  public boolean needsReEncryption(String stored) {
    if (stored == null || stored.isEmpty()) {
      return false;
    }
    if (!stored.startsWith(PREFIX)) {
      return true;
    }
    int end = stored.indexOf(SEPARATOR, PREFIX.length());
    return end < 0 || !stored.substring(PREFIX.length(), end).equals(current.id());
  }

  private String decryptVersioned(String stored) {
    int end = stored.indexOf(SEPARATOR, PREFIX.length());
    if (end < 0) {
      throw new SecretDecryptionException("Stored secret is malformed (no key id).");
    }
    String keyId = stored.substring(PREFIX.length(), end);
    KeyEntry key = decryptionKeys.get(keyId);
    if (key == null) {
      throw new SecretDecryptionException(
          "Stored secret was encrypted with key id '"
              + keyId
              + "', which is neither the current nor the previous encryption key.");
    }
    byte[] payload;
    try {
      payload = DECODER.decode(stored.substring(end + 1));
    } catch (IllegalArgumentException e) {
      throw new SecretDecryptionException("Stored secret is malformed (bad encoding).", e);
    }
    if (payload.length < NONCE_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
      throw new SecretDecryptionException("Stored secret is malformed (too short).");
    }
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(
          Cipher.DECRYPT_MODE,
          key.dataKey(),
          new GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, NONCE_LENGTH_BYTES));
      cipher.updateAAD(header(keyId));
      byte[] plain =
          cipher.doFinal(payload, NONCE_LENGTH_BYTES, payload.length - NONCE_LENGTH_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (AEADBadTagException e) {
      throw new SecretDecryptionException(
          "Stored secret failed its integrity check (key id '" + keyId + "').", e);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-256-GCM is required but unavailable", e);
    }
  }

  private String decryptLegacy(String stored) {
    List<RuntimeException> failures = new ArrayList<>();
    for (KeyEntry key : decryptionKeys.values()) {
      try {
        return key.legacy().decrypt(stored);
      } catch (RuntimeException e) {
        // Wrong key or corrupt value — try the next configured key.
        failures.add(e);
      }
    }
    SecretDecryptionException failure =
        new SecretDecryptionException(
            "Stored legacy-format secret cannot be decrypted with any configured encryption key.");
    failures.forEach(failure::addSuppressed);
    throw failure;
  }

  private static byte[] header(String keyId) {
    return (PREFIX + keyId).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * One configured encryption key: the high-entropy key material and its hex-encoded salt.
   *
   * @param key the key material ({@code qnop.auth.encryption-key})
   * @param saltHex the hex-encoded salt ({@code qnop.auth.encryption-salt})
   */
  public record KeyMaterial(String key, String saltHex) {

    public KeyMaterial {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(saltHex, "saltHex");
    }

    @Override
    public String toString() {
      return "KeyMaterial[redacted]";
    }
  }

  private record KeyEntry(String id, SecretKey dataKey, TextEncryptor legacy) {

    static KeyEntry of(KeyMaterial material) {
      byte[] ikm = material.key().getBytes(StandardCharsets.UTF_8);
      byte[] salt = HexFormat.of().parseHex(material.saltHex());
      byte[] prk = Hkdf.extract(salt, ikm);
      String id = HexFormat.of().formatHex(Hkdf.expand(prk, KEY_ID_PURPOSE, KEY_ID_BYTES));
      SecretKey dataKey =
          new SecretKeySpec(Hkdf.expand(prk, DATA_KEY_PURPOSE, KEY_LENGTH_BYTES), "AES");
      return new KeyEntry(id, dataKey, Encryptors.delux(material.key(), material.saltHex()));
    }

    @Override
    public String toString() {
      return "KeyEntry[" + id + "]";
    }
  }
}
