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
package io.qnop.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.api.v1.model.FieldError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Bean Validation → {@code FieldError.code} mapping (issue #836). */
class ConstraintFieldErrorsTest {

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

  @SuppressWarnings("unused")
  static final class Sample {
    @NotNull String notNull;
    @NotBlank String notBlank = " ";
    @Email String email = "nope";

    @Pattern(regexp = "[a-z]+")
    String pattern = "ABC";

    @Min(1)
    int min = 0;

    @Max(10)
    int max = 11;

    @DecimalMin(value = "0.5", inclusive = false)
    BigDecimal exclusive = new BigDecimal("0.5");

    @Size(max = 3)
    String tooLong = "abcd";

    @Size(min = 2)
    String tooShort = "a";

    @Size(min = 3, max = 5)
    String range = "ab";

    @Size(min = 1)
    List<String> list = List.of();

    @NotBlank(message = "pick a name your team will recognise")
    String custom = "";
  }

  private static FieldError reasonFor(String field) {
    ConstraintViolation<Sample> violation =
        validator.validate(new Sample()).stream()
            .filter(v -> v.getPropertyPath().toString().equals(field))
            .findFirst()
            .orElseThrow();
    return ConstraintFieldErrors.withReason(
        new FieldError().field(field).message(violation.getMessage()), violation);
  }

  @Test
  void mapsPresenceAndFormatConstraintsWithoutParams() {
    assertThat(reasonFor("notNull").getCode()).isEqualTo("REQUIRED");
    assertThat(reasonFor("notBlank").getCode()).isEqualTo("REQUIRED");
    assertThat(reasonFor("email").getCode()).isEqualTo("INVALID_EMAIL");
    assertThat(reasonFor("pattern").getCode()).isEqualTo("INVALID_FORMAT");
    assertThat(reasonFor("pattern").getParams()).isNull();
  }

  @Test
  void mapsNumericBoundsWithTheirLimit() {
    assertThat(reasonFor("min").getCode()).isEqualTo("TOO_SMALL");
    assertThat(reasonFor("min").getParams()).isEqualTo(Map.of("min", "1"));
    assertThat(reasonFor("max").getCode()).isEqualTo("TOO_LARGE");
    assertThat(reasonFor("max").getParams()).isEqualTo(Map.of("max", "10"));
  }

  @Test
  void mapsStringSizesByWhichEndIsBounded() {
    assertThat(reasonFor("tooLong").getCode()).isEqualTo("TOO_LONG");
    assertThat(reasonFor("tooLong").getParams()).isEqualTo(Map.of("max", "3"));
    assertThat(reasonFor("tooShort").getCode()).isEqualTo("TOO_SHORT");
    assertThat(reasonFor("tooShort").getParams()).isEqualTo(Map.of("min", "2"));
    assertThat(reasonFor("range").getCode()).isEqualTo("LENGTH_OUT_OF_RANGE");
    assertThat(reasonFor("range").getParams()).isEqualTo(Map.of("min", "3", "max", "5"));
  }

  @Test
  void leavesWhatAGenericCodeWouldMisstateWithoutOne() {
    // A collection's size counts items, not characters.
    assertThat(reasonFor("list").getCode()).isNull();
    // "Greater than" is not "at least".
    assertThat(reasonFor("exclusive").getCode()).isNull();
    // A custom message says more than the generic reason.
    FieldError custom = reasonFor("custom");
    assertThat(custom.getCode()).isNull();
    assertThat(custom.getParams()).isNull();
    assertThat(custom.getMessage()).isEqualTo("pick a name your team will recognise");
  }

  @Test
  void toleratesAMissingViolation() {
    FieldError error = ConstraintFieldErrors.withReason(new FieldError().field("x"), null);
    assertThat(error.getCode()).isNull();
    assertThat(error.getParams()).isNull();
  }
}
