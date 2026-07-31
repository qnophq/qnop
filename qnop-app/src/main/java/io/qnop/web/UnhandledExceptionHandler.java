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
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The last resort: nothing else handled it, so it gets logged before it becomes a 500 (issue #659,
 * ADR-0054).
 *
 * <p>Without this, an unexpected failure — object storage unreachable, a constraint nobody
 * anticipated — surfaced to the servlet container, which logs it <em>after</em> the filter chain
 * has unwound. By then {@link RequestLogContextFilter} has cleared the context, so the one line
 * that matters most arrived without a request id, a user or a review. Handling it here keeps it
 * inside the filter chain, where the context still exists.
 *
 * <p><strong>Ordered last, deliberately.</strong> Spring picks the first advice with a matching
 * handler, and {@code Exception} matches everything: at default precedence this class would shadow
 * the specific handlers in {@link DocumentExceptionHandler} and turn every 403 and 404 into a 500.
 *
 * <p>What Spring and Spring Security own is rethrown rather than swallowed, for the same reason:
 * {@code AccessDeniedException} becomes a 403 in the security filter chain, and Spring MVC's own
 * exceptions already carry the status they mean.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class UnhandledExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(UnhandledExceptionHandler.class);

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> onUnexpected(Exception ex) throws Exception {
    if (ex instanceof AccessDeniedException
        || ex instanceof AuthenticationException
        || ex instanceof org.springframework.web.ErrorResponse) {
      throw ex;
    }

    // The stack trace is the point. The message is not repeated to the client: it
    // routinely names internals, and a caller who cannot fix it does not need them.
    log.error("Unhandled {} while serving the request", ex.getClass().getSimpleName(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            new ErrorResponse()
                .code("INTERNAL_ERROR")
                .message("The request could not be completed.")
                .timestamp(OffsetDateTime.now()));
  }
}
