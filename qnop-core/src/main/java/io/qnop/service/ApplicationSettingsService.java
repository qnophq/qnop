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
package io.qnop.service;

import io.qnop.entity.ApplicationSetting;
import io.qnop.repository.ApplicationSettingRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runtime access to the global application settings (issue #16). Reads are served lock-free from an
 * immutable snapshot held in an {@link java.util.concurrent.atomic.AtomicReference}; the snapshot
 * is rebuilt after a write commits ({@code afterCommit}), and registered {@link
 * SettingsChangeListener} beans are then notified of the changed keys.
 *
 * <p>The snapshot holds <em>effective, decrypted</em> values (so consumers like the mail subsystem
 * get the plaintext SMTP password); secrets are encrypted on write and re-masked only for the admin
 * API view ({@link #describeAll()}). The registry ({@link ApplicationSettingKey}) is authoritative
 * for keys, types and defaults; the DB rows (issue #13) are the persisted projection.
 */
@Service
public class ApplicationSettingsService {

  /** Attempts for an optimistic-locking write before giving up (issue #47). */
  private static final int MAX_WRITE_ATTEMPTS = 3;

  private final ApplicationSettingRepository repository;
  private final TextEncryptor textEncryptor;
  private final ConfigurationKeyRedactor redactor;
  private final List<SettingsChangeListener> listeners;
  private final TransactionTemplate transactionTemplate;

  private final java.util.concurrent.atomic.AtomicReference<Map<ApplicationSettingKey, String>>
      snapshot = new java.util.concurrent.atomic.AtomicReference<>(Map.of());

  public ApplicationSettingsService(
      ApplicationSettingRepository repository,
      TextEncryptor textEncryptor,
      ConfigurationKeyRedactor redactor,
      List<SettingsChangeListener> listeners,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.textEncryptor = textEncryptor;
    this.redactor = redactor;
    this.listeners = listeners;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @PostConstruct
  public void reload() {
    snapshot.set(buildSnapshot());
  }

  private Map<ApplicationSettingKey, String> buildSnapshot() {
    Map<String, ApplicationSetting> rows = new HashMap<>();
    repository.findAll().forEach(row -> rows.put(row.getSettingKey(), row));

    Map<ApplicationSettingKey, String> resolved = new EnumMap<>(ApplicationSettingKey.class);
    for (ApplicationSettingKey key : ApplicationSettingKey.values()) {
      ApplicationSetting row = rows.get(key.getKey());
      String value = row != null ? row.getSettingValue() : key.getDefaultValue();
      resolved.put(key, decryptIfSecret(key, value));
    }
    return Collections.unmodifiableMap(resolved);
  }

  // --- typed reads -----------------------------------------------------------

  public String getString(ApplicationSettingKey key) {
    return snapshot.get().get(key);
  }

  public int getInteger(ApplicationSettingKey key) {
    return Integer.parseInt(snapshot.get().get(key).trim());
  }

  public boolean getBoolean(ApplicationSettingKey key) {
    return Boolean.parseBoolean(snapshot.get().get(key));
  }

  /** All settings with their (redacted) current values, for the admin API. */
  public List<SettingDescriptor> describeAll() {
    Map<ApplicationSettingKey, String> current = snapshot.get();
    List<SettingDescriptor> descriptors = new ArrayList<>();
    for (ApplicationSettingKey key : ApplicationSettingKey.values()) {
      descriptors.add(
          new SettingDescriptor(
              key.getKey(),
              redactor.redact(key, current.get(key)),
              key.getType().name(),
              key.getDescription(),
              key.isSensitive(),
              key.getEnumOptions()));
    }
    return descriptors;
  }

  // --- writes ----------------------------------------------------------------

  /**
   * Applies a partial set of changes ({@code key -> raw value}). Every value is validated first,
   * outside the write transaction; unknown keys and invalid values are collected and rejected
   * together via {@link SettingsValidationException} so the caller can flag every bad field at
   * once, before anything is written. A {@link ConfigurationKeyRedactor#MASK} value for a sensitive
   * key means "unchanged". The snapshot refresh and listener notifications run after the write
   * commits.
   *
   * <p>The write of the validated changes runs in its own transaction; an optimistic-locking
   * conflict (a concurrent edit of the same setting, guarded by {@link ApplicationSetting}'s
   * {@code @Version}) retries the whole write up to {@value #MAX_WRITE_ATTEMPTS} times so the
   * losing writer re-reads the current row rather than clobbering it (issue #47).
   *
   * @param changes raw key/value pairs to apply
   * @param actor the editing user's id, or {@code null} when unattributed (wired in issue #17)
   */
  public void update(Map<String, String> changes, UUID actor) {
    Map<ApplicationSettingKey, String> resolved = resolveAndValidate(changes);
    if (resolved.isEmpty()) {
      return;
    }
    OptimisticRetry.execute(
        MAX_WRITE_ATTEMPTS,
        () -> transactionTemplate.executeWithoutResult(status -> writeResolved(resolved, actor)));
  }

  /**
   * Resolves each change to a known key and validates its value, collecting <em>all</em>
   * field-level violations before failing so the admin UI can flag every bad field at once. Mask
   * sentinels for sensitive keys are dropped (left unchanged). Throws {@link
   * SettingsValidationException} if anything is invalid. Pure, DB-free work — runs outside the
   * write transaction and is never retried.
   */
  private Map<ApplicationSettingKey, String> resolveAndValidate(Map<String, String> changes) {
    Map<ApplicationSettingKey, String> resolved = new LinkedHashMap<>();
    List<SettingFieldError> errors = new ArrayList<>();
    for (Map.Entry<String, String> entry : changes.entrySet()) {
      Optional<ApplicationSettingKey> match = ApplicationSettingKey.fromKey(entry.getKey());
      if (match.isEmpty()) {
        errors.add(new SettingFieldError(entry.getKey(), "unknown setting key"));
        continue;
      }
      ApplicationSettingKey key = match.get();
      String value = entry.getValue();
      if (key.isSensitive() && redactor.isMask(value)) {
        continue; // sentinel: leave the stored secret untouched
      }
      try {
        ValueValidator.validate(key, value);
      } catch (SettingValidationException e) {
        errors.add(new SettingFieldError(key.getKey(), e.getReason()));
        continue;
      }
      resolved.put(key, value);
    }
    if (!errors.isEmpty()) {
      throw new SettingsValidationException(errors);
    }
    return resolved;
  }

  private void writeResolved(Map<ApplicationSettingKey, String> resolved, UUID actor) {
    Set<ApplicationSettingKey> changed = EnumSet.noneOf(ApplicationSettingKey.class);
    for (Map.Entry<ApplicationSettingKey, String> entry : resolved.entrySet()) {
      ApplicationSettingKey key = entry.getKey();
      ApplicationSetting row =
          repository
              .findById(key.getKey())
              .orElseGet(() -> new ApplicationSetting(key.getKey(), null, key.getType()));
      row.setSettingValue(encryptIfSecret(key, entry.getValue()));
      row.setValueType(key.getType());
      row.setUpdatedBy(actor);
      repository.save(row);
      changed.add(key);
    }
    if (!changed.isEmpty()) {
      refreshAfterCommit(changed);
    }
  }

  private void refreshAfterCommit(Set<ApplicationSettingKey> changed) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              notifyChanged(changed);
            }
          });
    } else {
      notifyChanged(changed);
    }
  }

  private void notifyChanged(Set<ApplicationSettingKey> changed) {
    reload();
    listeners.forEach(listener -> listener.onSettingsChanged(changed));
  }

  private String encryptIfSecret(ApplicationSettingKey key, String value) {
    if (key.isSensitive() && value != null && !value.isBlank()) {
      return textEncryptor.encrypt(value);
    }
    return value;
  }

  private String decryptIfSecret(ApplicationSettingKey key, String value) {
    if (key.isSensitive() && value != null && !value.isBlank()) {
      return textEncryptor.decrypt(value);
    }
    return value;
  }

  /**
   * A self-contained, web-safe view of one setting — no entity types reach the web layer. {@code
   * allowedValues} lists the permitted options for {@code ENUM}-typed settings (empty otherwise) so
   * the admin UI can render a dropdown straight from the contract.
   */
  public record SettingDescriptor(
      String key,
      String value,
      String type,
      String description,
      boolean sensitive,
      List<String> allowedValues) {}

  // retained for symmetry with values()/keys() callers
  static List<ApplicationSettingKey> allKeys() {
    return Arrays.asList(ApplicationSettingKey.values());
  }
}
