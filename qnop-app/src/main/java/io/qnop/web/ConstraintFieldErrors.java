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

  /**
   * Adds the code and params of {@code violation}'s constraint to {@code error}, if it has any.
   * {@code params} is set only when the code has some, so it is absent (null) otherwise — the same
   * shape the settings path produces.
   */
  static FieldError withReason(FieldError error, ConstraintViolation<?> violation) {
    error.params(null);
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
      case "Min" -> bound(error, FieldErrorCode.TOO_SMALL, "min", attributes.get("value"));
      case "Max" -> bound(error, FieldErrorCode.TOO_LARGE, "max", attributes.get("value"));
      // An exclusive decimal bound ("greater than") is not what "at least"/"at most" say, so it
      // keeps its English message rather than a code that words it wrongly.
      case "DecimalMin" ->
          inclusive(attributes)
              ? bound(error, FieldErrorCode.TOO_SMALL, "min", attributes.get("value"))
              : error;
      case "DecimalMax" ->
          inclusive(attributes)
              ? bound(error, FieldErrorCode.TOO_LARGE, "max", attributes.get("value"))
              : error;
      // The length codes speak of characters; a sized collection counts items, so it keeps its
      // English message.
      case "Size" ->
          violation.getInvalidValue() instanceof CharSequence ? sized(error, attributes) : error;
      default -> error;
    };
  }

  private static boolean inclusive(Map<String, Object> attributes) {
    return !Boolean.FALSE.equals(attributes.get("inclusive"));
  }

  private static FieldError bound(
      FieldError error, FieldErrorCode code, String name, Object value) {
    return error.code(code.name()).params(Map.of(name, String.valueOf(value)));
  }

  /**
   * A string's {@code @Size}: an upper bound alone reads as "too long", a lower bound alone as "too
   * short", and a real range names both ends.
   */
  private static FieldError sized(FieldError error, Map<String, Object> attributes) {
    int min = (Integer) attributes.get("min");
    int max = (Integer) attributes.get("max");
    if (min == 0) {
      return bound(error, FieldErrorCode.TOO_LONG, "max", max);
    }
    if (max == Integer.MAX_VALUE) {
      return bound(error, FieldErrorCode.TOO_SHORT, "min", min);
    }
    return error
        .code(FieldErrorCode.LENGTH_OUT_OF_RANGE.name())
        .params(Map.of("min", String.valueOf(min), "max", String.valueOf(max)));
  }
}
