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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit-tests the {@link ValidSecret} constraint via a standalone Bean Validation {@link Validator}.
 */
class SecretValidatorTest {

  /** Minimal carrier so the constraint is exercised exactly as on a record component. */
  record Holder(@ValidSecret String secret) {}

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        "short",
        "too-short-to-be-secure", // 22 chars, below the 32 minimum
        "CHANGE_ME",
        "change-me",
        "changeme",
        "password",
        "qnop"
      })
  void rejectsWeakOrDefaultSecrets(String value) {
    assertFalse(
        validator.validate(new Holder(value)).isEmpty(), "expected a violation for: " + value);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"this-is-a-strong-test-secret-0123456789", "Zk9$fJ2!pQ7@xL4&mN1#vB8^cD6*aS3-eR5"})
  void acceptsStrongSecrets(String value) {
    assertTrue(
        validator.validate(new Holder(value)).isEmpty(), "expected no violation for: " + value);
  }
}
