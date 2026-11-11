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
package io.qnop.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.entity.AuditEvent;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.OidcProviderRepository;
import io.qnop.security.SecretCipher;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.secrets.SecretReEncryptionService;
import io.qnop.service.secrets.SecretReEncryptionService.Result;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.encrypt.Encryptors;

/**
 * Stored secrets move to the versioned format and current key (issue #927, ADR-0022 amendment).
 *
 * <p>The legacy values are written straight into the tables, exactly as a pre-#927 instance left
 * them, and the pass is then run as it runs at start-up.
 */
class SecretReEncryptionIT extends SeededIntegrationTest {

  /** The integration-test key/salt (AbstractIntegrationTest) — what a pre-#927 instance used. */
  private static final String KEY = "integration-test-encryption-key-0123456789";

  private static final String SALT = "0123456789abcdef0123456789abcdef";

  /** The seeded, enabled OIDC provider — its secret is legacy ciphertext of this plaintext. */
  private static final UUID SEEDED_PROVIDER =
      UUID.fromString("d0000000-0000-0000-0000-000000000001");

  private static final String SEEDED_PROVIDER_SECRET = "seed-dummy-secret";

  @Autowired private SecretReEncryptionService reEncryption;
  @Autowired private SecretCipher cipher;
  @Autowired private ApplicationSettingsService settings;
  @Autowired private OidcProviderRepository oidcProviders;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void restoreSmtpPassword() {
    jdbc.update(
        "UPDATE application_setting SET setting_value = '' WHERE setting_key = ?", "smtp.password");
    settings.reload();
  }

  @Test
  @DisplayName("legacy setting and OIDC secrets are re-encrypted and still read the same")
  void migratesLegacyCiphertext() {
    storeSmtpPassword(Encryptors.delux(KEY, SALT).encrypt("legacy-smtp-password"));
    assertThat(cipher.needsReEncryption(storedClientSecret())).as("seed is legacy").isTrue();

    Result result = reEncryption.reEncryptAll();

    assertThat(result.settingsRewritten()).isEqualTo(1);
    assertThat(result.oidcClientSecretsRewritten()).isEqualTo(1);
    assertThat(result.hasUnreadable()).isFalse();

    String smtp = storedSmtpPassword();
    assertThat(smtp).startsWith("v2:" + cipher.currentKeyId() + ":");
    assertThat(storedClientSecret()).startsWith("v2:" + cipher.currentKeyId() + ":");

    settings.reload();
    assertThat(settings.getString(ApplicationSettingKey.SMTP_PASSWORD))
        .isEqualTo("legacy-smtp-password");
    assertThat(oidcProviders.findById(SEEDED_PROVIDER).orElseThrow().getClientSecret())
        .isEqualTo(SEEDED_PROVIDER_SECRET);

    AuditEvent event = latest("secrets.reencrypted");
    assertThat(event.getActorId()).isNull();
    assertThat(event.getDetail())
        .doesNotContain("legacy-smtp-password")
        .doesNotContain(SEEDED_PROVIDER_SECRET);
  }

  @Test
  @DisplayName("a second pass finds nothing to do")
  void isIdempotent() {
    storeSmtpPassword(Encryptors.delux(KEY, SALT).encrypt("legacy-smtp-password"));
    reEncryption.reEncryptAll();
    String afterFirst = storedSmtpPassword();
    long audits = count("secrets.reencrypted");

    Result second = reEncryption.reEncryptAll();

    assertThat(second.rewritten()).isZero();
    assertThat(storedSmtpPassword()).isEqualTo(afterFirst);
    assertThat(count("secrets.reencrypted")).isEqualTo(audits);
  }

  @Test
  @DisplayName("a secret no configured key can read is kept, reported, and does not break reads")
  void reportsUnreadableSecretsWithoutLosingThem() {
    String foreign =
        new SecretCipher(
                new SecretCipher.KeyMaterial("some-other-deployments-key-0123456789", SALT), null)
            .encrypt("unrecoverable");
    storeSmtpPassword(foreign);

    Result result = reEncryption.reEncryptAll();

    assertThat(result.unreadableSettings()).containsExactly("smtp.password");
    assertThat(storedSmtpPassword()).as("ciphertext preserved").isEqualTo(foreign);
    AuditEvent event = latest("secrets.unreadable");
    assertThat(event.getDetail()).contains("smtp.password").doesNotContain(foreign);

    // The snapshot treats it as unset instead of failing — the instance keeps running.
    settings.reload();
    assertThat(settings.getString(ApplicationSettingKey.SMTP_PASSWORD)).isNull();
  }

  @Test
  @DisplayName("an unreadable OIDC client secret survives an unrelated edit of its provider")
  void unrelatedProviderEditKeepsAnUnreadableSecret() {
    String foreign =
        new SecretCipher(
                new SecretCipher.KeyMaterial("some-other-deployments-key-0123456789", SALT), null)
            .encrypt("unrecoverable");
    jdbc.update(
        "UPDATE oidc_provider SET client_secret_encrypted = ? WHERE id = ?",
        foreign,
        SEEDED_PROVIDER);

    var provider = oidcProviders.findById(SEEDED_PROVIDER).orElseThrow();
    assertThat(provider.getClientSecret()).as("reads as unset").isNull();
    provider.setEnabled(false);
    oidcProviders.saveAndFlush(provider);

    assertThat(storedClientSecret()).as("ciphertext preserved").isEqualTo(foreign);
    assertThat(reEncryption.reEncryptAll().unreadableOidcProviders())
        .containsExactly("Seeded Google");
  }

  @Test
  @DisplayName("an administrator's write is stored in the current format")
  void writesUseTheCurrentFormat() {
    settings.update(Map.of("smtp.password", "fresh-password"), ADMIN_ID);

    assertThat(storedSmtpPassword()).startsWith("v2:" + cipher.currentKeyId() + ":");
    assertThat(cipher.needsReEncryption(storedSmtpPassword())).isFalse();
  }

  private void storeSmtpPassword(String stored) {
    jdbc.update(
        "UPDATE application_setting SET setting_value = ? WHERE setting_key = ?",
        stored,
        "smtp.password");
  }

  private String storedSmtpPassword() {
    return jdbc.queryForObject(
        "SELECT setting_value FROM application_setting WHERE setting_key = ?",
        String.class,
        "smtp.password");
  }

  private String storedClientSecret() {
    return jdbc.queryForObject(
        "SELECT client_secret_encrypted FROM oidc_provider WHERE id = ?",
        String.class,
        SEEDED_PROVIDER);
  }

  private AuditEvent latest(String type) {
    return auditEvents.findAll().stream()
        .filter(event -> type.equals(event.getEventType()))
        .max(Comparator.comparing(AuditEvent::getCreatedAt))
        .orElseThrow(() -> new AssertionError("no " + type + " audit event"));
  }

  private long count(String type) {
    return auditEvents.findAll().stream().filter(e -> type.equals(e.getEventType())).count();
  }
}
