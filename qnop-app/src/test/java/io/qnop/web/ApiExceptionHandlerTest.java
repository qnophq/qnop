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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.spi.storage.StorageException;
import io.qnop.spi.storage.StorageUnavailableException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unit tests for {@link ApiExceptionHandler}: the constraint-violation 400 (issue #169) and the 503
 * for an unavailable object store (issue #983).
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

  /** A controller that fails as a request does when object storage is down. */
  @RestController
  static final class FailingController {
    @GetMapping("/unavailable")
    String unavailable() {
      throw new StorageUnavailableException(
          "Failed to read object", new IOException("Connect to http://minio:9000 failed"));
    }

    @GetMapping("/refused")
    String refused() {
      throw new StorageException("Rejected storage key with an unexpected format");
    }
  }

  private static MockMvc mvc() {
    // Both advices, in the application's order: the catch-all comes last, so it must not be what
    // answers a storage outage.
    return MockMvcBuilders.standaloneSetup(new FailingController())
        .setControllerAdvice(new ApiExceptionHandler(), new UnhandledExceptionHandler())
        .build();
  }

  @Test
  @DisplayName("an unavailable object store answers 503 STORAGE_UNAVAILABLE, not a generic 500")
  void unavailableStorageIs503() throws Exception {
    mvc()
        .perform(get("/unavailable"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
        // The internals of the failure stay in the log.
        .andExpect(jsonPath("$.message").value(not(containsString("minio"))));
  }

  @Test
  @DisplayName("any other storage failure is still a 500")
  void otherStorageFailureIs500() throws Exception {
    mvc()
        .perform(get("/refused"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
  }
}
