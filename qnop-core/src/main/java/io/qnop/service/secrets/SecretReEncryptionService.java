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
package io.qnop.service.secrets;

import io.qnop.entity.ApplicationSetting;
import io.qnop.entity.AuditEvent;
import io.qnop.repository.ApplicationSettingRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.OidcProviderRepository;
import io.qnop.repository.OidcProviderRepository.StoredClientSecret;
import io.qnop.security.SecretCipher;
import io.qnop.service.ApplicationSettingKey;
import java.util.ArrayList;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Brings every stored secret to the current ciphertext format and key (issue #927, ADR-0022
 * amendment): values in the legacy pre-#927 format, and values written under the previous
 * (rotation) key, are decrypted and re-encrypted under the current key.
 *
 * <p><b>When.</b> Once per instance start, after the application is ready. Changing the encryption
 * key means restarting with new configuration, so a start-up pass is exactly when there can be
 * anything to do; ordinary writes always use the current format already.
 *
 * <p><b>Safe to repeat and to race.</b> Values already current are skipped, so a second run is a
 * no-op. A ShedLock (ADR-0029) keeps instances starting together from working at once, and each
 * rewrite is a compare-and-set on the ciphertext that was read, so a concurrent administrator edit
 * or another instance is never overwritten even without the lock.
 *
 * <p><b>Unreadable values.</b> A value no configured key can decrypt is left untouched (restoring
 * the right key recovers it) and reported: a loud error log and a {@value #AUDIT_UNREADABLE} SYSTEM
 * audit event naming the setting or provider — never the value. The instance still starts; the
 * secret reads as unset until the key is restored or an administrator enters it again.
 */
@Service
public class SecretReEncryptionService {

  /** The ShedLock name of the start-up pass. */
  public static final String LOCK_NAME = "secretReEncryption";

  /** SYSTEM audit event: stored secrets were rewritten to the current format/key. */
  static final String AUDIT_REENCRYPTED = "secrets.reencrypted";

  /** SYSTEM audit event: stored secrets could not be decrypted with any configured key. */
  static final String AUDIT_UNREADABLE = "secrets.unreadable";

  private static final Logger log = LoggerFactory.getLogger(SecretReEncryptionService.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final SecretCipher cipher;
  private final ApplicationSettingRepository settings;
  private final OidcProviderRepository oidcProviders;
  private final AuditEventRepository auditEvents;
  private final TransactionTemplate transactionTemplate;

  public SecretReEncryptionService(
      SecretCipher cipher,
      ApplicationSettingRepository settings,
      OidcProviderRepository oidcProviders,
      AuditEventRepository auditEvents,
      PlatformTransactionManager transactionManager) {
    this.cipher = cipher;
    this.settings = settings;
    this.oidcProviders = oidcProviders;
    this.auditEvents = auditEvents;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** The start-up trigger; runs through the bean proxy, so the ShedLock applies. */
  @EventListener(ApplicationReadyEvent.class)
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
  public void reEncryptOnStartup() {
    reEncryptAll();
  }

  /**
   * One pass over every stored secret.
   *
   * @return what the pass did
   */
  public Result reEncryptAll() {
    Tally settingsTally = reEncryptSettings();
    Tally oidcTally = reEncryptOidcClientSecrets();
    Result result =
        new Result(
            settingsTally.rewritten,
            oidcTally.rewritten,
            List.copyOf(settingsTally.unreadable),
            List.copyOf(oidcTally.unreadable));
    report(result);
    return result;
  }

  private Tally reEncryptSettings() {
    Tally tally = new Tally();
    for (ApplicationSetting row : settings.findAll()) {
      boolean sensitive =
          ApplicationSettingKey.fromKey(row.getSettingKey())
              .map(ApplicationSettingKey::isSensitive)
              .orElse(false);
      String stored = row.getSettingValue();
      if (!sensitive || stored == null || stored.isBlank()) {
        continue;
      }
      reEncrypt(
          row.getSettingKey(),
          stored,
          tally,
          next -> settings.replaceStoredValue(row.getSettingKey(), stored, next));
    }
    return tally;
  }

  private Tally reEncryptOidcClientSecrets() {
    Tally tally = new Tally();
    for (StoredClientSecret secret : oidcProviders.findStoredClientSecrets()) {
      String stored = secret.getCiphertext();
      reEncrypt(
          secret.getName(),
          stored,
          tally,
          next -> oidcProviders.replaceStoredClientSecret(secret.getId(), stored, next));
    }
    return tally;
  }

  private void reEncrypt(String name, String stored, Tally tally, CompareAndSet replacement) {
    if (!cipher.needsReEncryption(stored)) {
      return;
    }
    String plaintext;
    try {
      plaintext = cipher.decrypt(stored);
    } catch (IllegalStateException e) {
      tally.unreadable.add(name);
      return;
    }
    String next = cipher.encrypt(plaintext);
    Integer updated = transactionTemplate.execute(status -> replacement.replace(next));
    if (updated != null && updated > 0) {
      tally.rewritten++;
    }
    // 0 rows: changed concurrently — whoever changed it wrote the current format.
  }

  private void report(Result result) {
    if (result.rewritten() > 0) {
      log.info(
          "Re-encrypted {} stored setting secret(s) and {} OIDC client secret(s) under the current"
              + " encryption key.",
          result.settingsRewritten(),
          result.oidcClientSecretsRewritten());
      ObjectNode detail = MAPPER.createObjectNode();
      detail.put("settings", result.settingsRewritten());
      detail.put("oidcProviders", result.oidcClientSecretsRewritten());
      detail.put("keyId", cipher.currentKeyId());
      saveAudit(AUDIT_REENCRYPTED, detail);
    }
    if (result.hasUnreadable()) {
      log.error(
          "Stored secrets could not be decrypted with any configured encryption key and are treated"
              + " as unset: settings={}, oidcProviders={}. Restore the key that wrote them (as"
              + " qnop.auth.previous-encryption-key) or enter the secrets again.",
          result.unreadableSettings(),
          result.unreadableOidcProviders());
      ObjectNode detail = MAPPER.createObjectNode();
      ArrayNode settingKeys = detail.putArray("settings");
      result.unreadableSettings().forEach(settingKeys::add);
      ArrayNode providers = detail.putArray("oidcProviders");
      result.unreadableOidcProviders().forEach(providers::add);
      saveAudit(AUDIT_UNREADABLE, detail);
    }
  }

  private void saveAudit(String eventType, ObjectNode detail) {
    transactionTemplate.executeWithoutResult(
        status -> auditEvents.save(AuditEvent.system(eventType, null, detail.toString())));
  }

  /** Replaces the stored value read earlier with {@code next}; returns the rows changed. */
  @FunctionalInterface
  private interface CompareAndSet {
    int replace(String next);
  }

  private static final class Tally {
    private int rewritten;
    private final List<String> unreadable = new ArrayList<>();
  }

  /**
   * The outcome of one pass. Names only — never values.
   *
   * @param settingsRewritten setting secrets rewritten to the current format/key
   * @param oidcClientSecretsRewritten OIDC client secrets rewritten to the current format/key
   * @param unreadableSettings keys of setting secrets no configured key could decrypt
   * @param unreadableOidcProviders names of OIDC providers whose secret no configured key could
   *     decrypt
   */
  public record Result(
      int settingsRewritten,
      int oidcClientSecretsRewritten,
      List<String> unreadableSettings,
      List<String> unreadableOidcProviders) {

    public int rewritten() {
      return settingsRewritten + oidcClientSecretsRewritten;
    }

    public boolean hasUnreadable() {
      return !unreadableSettings.isEmpty() || !unreadableOidcProviders.isEmpty();
    }
  }
}
