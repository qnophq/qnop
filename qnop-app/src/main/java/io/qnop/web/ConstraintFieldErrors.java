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

import io.qnop.api.v1.model.FieldError;
import io.qnop.service.FieldErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.util.Map;

/**
 * Derives the stable {@code FieldError.code} and {@code params} (issue #836) from a Bean Validation
 * constraint, so a client can word a body-validation failure in the user's language.
 *
 * <p>Only a constraint that still carries its built-in message gets a code: a custom message (a
 * password policy, a domain rule) says something the generic reason would lose, so it travels as
 * {@code message} alone and the client shows it verbatim.
 */
final class ConstraintFieldErrors {

  private static final String BUILT_IN_MESSAGE_PREFIX = "{jakarta.validation.constraints.";

  private ConstraintFieldErrors() {}

  /** Adds the code and params of {@code violation}'s constraint to {@code error}, if it has any. */
  static FieldError withReason(FieldError error, ConstraintViolation<?> violation) {
    if (violation == null) {
      return error;
    }
    ConstraintDescriptor<?> descriptor = violation.getConstraintDescriptor();
    if (descriptor == null
        || descriptor.getMessageTemplate() == null
        || !descriptor.getMessageTemplate().startsWith(BUILT_IN_MESSAGE_PREFIX)) {
      return error;
    }
    Map<String, Object> attributes = descriptor.getAttributes();
    return switch (descriptor.getAnnotation().annotationType().getSimpleName()) {
      case "NotNull", "NotBlank", "NotEmpty" -> error.code(FieldErrorCode.REQUIRED.name());
      case "Email" -> error.code(FieldErrorCode.INVALID_EMAIL.name());
      case "Pattern" -> error.code(FieldErrorCode.INVALID_FORMAT.name());
      case "Min", "DecimalMin" ->
          error
              .code(FieldErrorCode.TOO_SMALL.name())
              .params(Map.of("min", String.valueOf(attributes.get("value"))));
      case "Max", "DecimalMax" ->
          error
              .code(FieldErrorCode.TOO_LARGE.name())
              .params(Map.of("max", String.valueOf(attributes.get("value"))));
      case "Size" -> sized(error, attributes);
      default -> error;
    };
  }

  /** {@code @Size} with only an upper bound reads as "too long"; a real range names both ends. */
  private static FieldError sized(FieldError error, Map<String, Object> attributes) {
    String min = String.valueOf(attributes.get("min"));
    String max = String.valueOf(attributes.get("max"));
    if ("0".equals(min)) {
      return error.code(FieldErrorCode.TOO_LONG.name()).params(Map.of("max", max));
    }
    return error
        .code(FieldErrorCode.LENGTH_OUT_OF_RANGE.name())
        .params(Map.of("min", min, "max", max));
  }
}
