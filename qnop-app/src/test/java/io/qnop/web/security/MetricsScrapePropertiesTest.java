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
package io.qnop.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scrape-token setting (issue #962). */
class MetricsScrapePropertiesTest {

  private static boolean isValid(MetricsScrapeProperties properties) {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Validator validator = factory.getValidator();
      return validator.validate(properties).isEmpty();
    }
  }

  @Test
  @DisplayName("treats a blank token as unset, the way an empty environment variable binds")
  void blankMeansOff() {
    assertThat(new MetricsScrapeProperties("").scrapeToken()).isNull();
    assertThat(new MetricsScrapeProperties("   ").scrapeToken()).isNull();
    assertThat(isValid(new MetricsScrapeProperties(""))).isTrue();
    assertThat(isValid(new MetricsScrapeProperties(null))).isTrue();
  }

  @Test
  @DisplayName("rejects a token shorter than 32 characters")
  void rejectsAShortToken() {
    assertThat(isValid(new MetricsScrapeProperties("a".repeat(31)))).isFalse();
    assertThat(isValid(new MetricsScrapeProperties("a".repeat(32)))).isTrue();
  }
}
