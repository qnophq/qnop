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

/**
 * The branding slots an operator can upload (ported from plugwerk, issue #15). Each slot holds at
 * most one {@link ApplicationAsset} — re-uploading replaces the row rather than versioning it.
 *
 * <p>Every constant carries two stable string forms: {@link #dbValue} (snake_case) is what lands in
 * the {@code application_asset.slot} column and the Postgres {@code CHECK}, while {@link #urlValue}
 * (kebab-case) is the form used on the read path (e.g. {@code /api/v1/branding/{slot}}, issue #23).
 * The column stores {@link #dbValue} via {@link BrandingSlotConverter}, not the enum name, so the
 * persisted values match the {@code CHECK} domain exactly.
 */
public enum BrandingSlot {
  LOGO_LIGHT("logo_light", "logo-light"),
  LOGO_DARK("logo_dark", "logo-dark"),
  LOGOMARK("logomark", "logomark");

  private final String dbValue;
  private final String urlValue;

  BrandingSlot(String dbValue, String urlValue) {
    this.dbValue = dbValue;
    this.urlValue = urlValue;
  }

  /** The snake_case value persisted in {@code application_asset.slot}. */
  public String dbValue() {
    return dbValue;
  }

  /** The kebab-case value used in branding URLs (issue #23). */
  public String urlValue() {
    return urlValue;
  }

  /** Resolves a slot from its {@link #dbValue}, or throws if unknown. */
  public static BrandingSlot fromDbValue(String value) {
    for (BrandingSlot slot : values()) {
      if (slot.dbValue.equals(value)) {
        return slot;
      }
    }
    throw new IllegalArgumentException("Unknown branding slot db value: " + value);
  }

  /** Resolves a slot from its {@link #urlValue}, or {@code null} if unknown. */
  public static BrandingSlot fromUrlValue(String value) {
    for (BrandingSlot slot : values()) {
      if (slot.urlValue.equals(value)) {
        return slot;
      }
    }
    return null;
  }
}
