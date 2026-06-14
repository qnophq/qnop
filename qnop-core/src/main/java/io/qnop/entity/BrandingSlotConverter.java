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

/**
 * Maps {@link BrandingSlot} to its snake_case {@link BrandingSlot#dbValue()} for the {@code
 * application_asset.slot} column, instead of the default {@code @Enumerated(STRING)} enum-name
 * mapping. This keeps the persisted values aligned with the Postgres {@code CHECK} domain ({@code
 * 'logo_light' | 'logo_dark' | 'logomark'}, see migration {@code 0005}).
 */
@Converter
public class BrandingSlotConverter implements AttributeConverter<BrandingSlot, String> {

  @Override
  public String convertToDatabaseColumn(BrandingSlot slot) {
    return slot == null ? null : slot.dbValue();
  }

  @Override
  public BrandingSlot convertToEntityAttribute(String dbValue) {
    return dbValue == null ? null : BrandingSlot.fromDbValue(dbValue);
  }
}
