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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A global, typed application setting keyed by {@code setting_key} (the natural primary key). The
 * runtime settings service (issue #16) reads and writes these; superadmins edit them via the admin
 * API.
 *
 * <p>There is intentionally no {@code created_at} (matches the reference model). {@code updatedBy}
 * is a nullable raw UUID reference to the editing {@link User}; the Postgres {@code ON DELETE SET
 * NULL} foreign key and the {@code value_type} {@code CHECK} live in Liquibase, not in JPA
 * annotations (ADR-0020).
 */
@Entity
@Table(name = "application_setting")
public class ApplicationSetting {

  @Id
  @Column(name = "setting_key", nullable = false, updatable = false)
  private String settingKey;

  @Column(name = "setting_value")
  private String settingValue;

  @Column(name = "setting_desc")
  private String settingDesc;

  @Enumerated(EnumType.STRING)
  @Column(name = "value_type", nullable = false, length = 16)
  private SettingValueType valueType;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  /**
   * Optimistic-locking guard (issue #47). Hibernate increments it on every update and rejects a
   * write whose in-memory version is stale, so two concurrent admin edits of the same setting can't
   * silently lose one another. The backing column ({@code version BIGINT NOT NULL DEFAULT 0}) is
   * defined in Liquibase on the {@code application_setting} table (migration 0002).
   */
  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected ApplicationSetting() {
    // for JPA
  }

  public ApplicationSetting(String settingKey, String settingValue, SettingValueType valueType) {
    this.settingKey = settingKey;
    this.settingValue = settingValue;
    this.valueType = valueType;
  }

  public String getSettingKey() {
    return settingKey;
  }

  public String getSettingValue() {
    return settingValue;
  }

  public void setSettingValue(String settingValue) {
    this.settingValue = settingValue;
  }

  public String getSettingDesc() {
    return settingDesc;
  }

  public void setSettingDesc(String settingDesc) {
    this.settingDesc = settingDesc;
  }

  public SettingValueType getValueType() {
    return valueType;
  }

  public void setValueType(SettingValueType valueType) {
    this.valueType = valueType;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(UUID updatedBy) {
    this.updatedBy = updatedBy;
  }

  /** The optimistic-locking version; managed by Hibernate (no setter). */
  public long getVersion() {
    return version;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ApplicationSetting other)) {
      return false;
    }
    return settingKey != null && settingKey.equals(other.settingKey);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(settingKey);
  }
}
