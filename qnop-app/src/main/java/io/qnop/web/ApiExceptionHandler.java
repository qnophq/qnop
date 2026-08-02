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

import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.FieldError;
import io.qnop.service.limits.FeatureDisabledException;
import io.qnop.service.limits.InstanceLimitExceededException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Global error handler (issue #45): maps request-validation failures onto the uniform {@link
 * ErrorResponse} envelope so every {@code 400} has the same shape as the rest of the API. The
 * filter-chain errors (401/403/429) are handled at their source via {@link ApiErrorWriter}; domain
 * errors keep their controller-local handlers, which already return {@link ErrorResponse}.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> onBodyValidation(MethodArgumentNotValidException ex) {
    List<FieldError> fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    new FieldError()
                        .field(error.getField())
                        .message(
                            error.getDefaultMessage() != null
                                ? error.getDefaultMessage()
                                : "is invalid"))
            .toList();
    return validationError(fieldErrors);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ErrorResponse> onParameterValidation(HandlerMethodValidationException ex) {
    return badRequest("request parameter validation failed");
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> onConstraintViolation(ConstraintViolationException ex) {
    // Never echo ex.getMessage(): it embeds the internal property path (e.g.
    // "createUser.arg0.email"), leaking method and parameter names. Surface only the constraint's
    // interpolated message keyed by the leaf node (the public field/parameter name).
    List<FieldError> fieldErrors =
        ex.getConstraintViolations().stream()
            .map(
                violation ->
                    new FieldError()
                        .field(leafNode(violation.getPropertyPath()))
                        .message(
                            violation.getMessage() != null ? violation.getMessage() : "is invalid"))
            .toList();
    return fieldErrors.isEmpty()
        ? badRequest("request parameter validation failed")
        : validationError(fieldErrors);
  }

  /**
   * The last node of a constraint property path — the public field/parameter name, not the path.
   */
  private static String leafNode(Path propertyPath) {
    String leaf = "";
    for (Path.Node node : propertyPath) {
      if (node.getName() != null) {
        leaf = node.getName();
      }
    }
    return leaf;
  }

  /**
   * A quota is full (issue #673).
   *
   * <p>409 rather than 403: the caller is entitled to do this and asked for it correctly — the
   * deployment has no room. That is a conflict with the current state, which is what 409 means, and
   * telling the two apart matters to whoever has to act on it: a permission problem is solved by an
   * administrator, a full quota by the operator of the deployment.
   *
   * <p>The message carries the ceiling. "Refused" without a number leaves the reader guessing at
   * how much room they would need to free.
   */
  @ExceptionHandler(InstanceLimitExceededException.class)
  ResponseEntity<ErrorResponse> limitExceeded(InstanceLimitExceededException ex) {
    log.info("Refused: {} ({})", ex.getMessage(), ex.code());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ErrorResponse()
                .code(ex.code())
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now()));
  }

  /**
   * A capability this deployment does not offer (issue #674).
   *
   * <p>403 and not 409: nothing is full, and no amount of waiting or freeing changes the answer.
   * Not 404 either — pretending the capability does not exist would leave an administrator hunting
   * for a setting instead of asking whoever operates the deployment.
   */
  @ExceptionHandler(FeatureDisabledException.class)
  ResponseEntity<ErrorResponse> featureDisabled(FeatureDisabledException ex) {
    log.info("Refused: {} ({})", ex.getMessage(), ex.code());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            new ErrorResponse()
                .code(ex.code())
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now()));
  }

  private static ResponseEntity<ErrorResponse> badRequest(String message) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new ErrorResponse()
                .code("VALIDATION_ERROR")
                .message(message)
                .timestamp(OffsetDateTime.now()));
  }

  private static ResponseEntity<ErrorResponse> validationError(List<FieldError> fieldErrors) {
    String message =
        fieldErrors.isEmpty()
            ? "request body validation failed"
            : fieldErrors.size() == 1
                ? "1 field is invalid."
                : fieldErrors.size() + " fields are invalid.";
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new ErrorResponse()
                .code("VALIDATION_ERROR")
                .message(message)
                .fieldErrors(fieldErrors)
                .timestamp(OffsetDateTime.now()));
  }
}
