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

import io.qnop.api.v1.model.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link ApiExceptionHandler}, focused on the constraint-violation 400 (issue #169).
 */
class ApiExceptionHandlerTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static final class Sample {
    @NotBlank(message = "must not be blank")
    private final String email;

    Sample(String value) {
      this.email = value;
    }
  }

  @Test
  @DisplayName(
      "constraint-violation 400 returns a generic message + field errors, "
          + "never the raw exception message (which embeds the property path)")
  void doesNotEchoRawExceptionMessage() {
    Set<ConstraintViolation<Sample>> violations = validator.validate(new Sample("  "));
    ConstraintViolationException ex = new ConstraintViolationException(violations);
    // The raw exception message embeds the property path ("email: must not be blank"); echoing it
    // is exactly what leaks method/parameter paths for method-level constraints.
    assertThat(ex.getMessage()).contains("email");

    ResponseEntity<ErrorResponse> response = handler.onConstraintViolation(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    ErrorResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getCode()).isEqualTo("VALIDATION_ERROR");
    // The envelope message is the safe generic count, not ex.getMessage().
    assertThat(body.getMessage()).isEqualTo("1 field is invalid.").isNotEqualTo(ex.getMessage());
    // Field errors carry the public leaf name + the interpolated constraint message only.
    assertThat(body.getFieldErrors())
        .singleElement()
        .satisfies(
            fe -> {
              assertThat(fe.getField()).isEqualTo("email");
              assertThat(fe.getMessage()).isEqualTo("must not be blank");
            });
  }

  @Test
  @DisplayName("an empty constraint set falls back to a generic message")
  void emptyViolationsFallsBackToGenericMessage() {
    ResponseEntity<ErrorResponse> response =
        handler.onConstraintViolation(new ConstraintViolationException(Set.of()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getMessage()).isEqualTo("request parameter validation failed");
  }
}
