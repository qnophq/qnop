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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApplicationSettingsService#parseIntSetting(String, String)} — the
 * null/blank guard added for issue #340 so a missing or malformed integer setting fails with a
 * clear, key-named message instead of an opaque NPE / NumberFormatException.
 */
class ApplicationSettingsServiceTest {

  @Test
  void parsesAValidInteger() {
    assertThat(ApplicationSettingsService.parseIntSetting("upload.document_max_file_size_mb", "25"))
        .isEqualTo(25);
  }

  @Test
  void trimsSurroundingWhitespace() {
    assertThat(ApplicationSettingsService.parseIntSetting("smtp.port", "  587 ")).isEqualTo(587);
  }

  @Test
  void rejectsNullWithAKeyNamedMessage() {
    assertThatThrownBy(() -> ApplicationSettingsService.parseIntSetting("smtp.port", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("smtp.port")
        .hasNoCause();
  }

  @Test
  void rejectsBlank() {
    assertThatThrownBy(() -> ApplicationSettingsService.parseIntSetting("smtp.port", "   "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("smtp.port");
  }

  @Test
  void rejectsNonNumericWithTheOriginalNumberFormatExceptionAsCause() {
    assertThatThrownBy(
            () -> ApplicationSettingsService.parseIntSetting("smtp.port", "not-a-number"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("smtp.port")
        .hasMessageContaining("not-a-number")
        .hasCauseInstanceOf(NumberFormatException.class);
  }
}
