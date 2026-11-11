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
package io.qnop.web.security.ratelimit;

import io.qnop.api.v1.model.ErrorResponse;
import java.time.OffsetDateTime;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders a drained per-identifier bucket (issue #899) exactly like the per-IP filters do: {@code
 * 429}, {@code Retry-After}, code {@code RATE_LIMITED} and the endpoint's usual message — so a
 * client cannot tell which of the two limits refused it.
 *
 * <p>Ordered explicitly ahead of {@code UnhandledExceptionHandler}: it, too, sits at the lowest
 * precedence, and an unordered advice ties with it — whichever Spring meets first would win, and
 * the catch-all turns this into a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitExceptionHandler {

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<ErrorResponse> onRateLimited(RateLimitExceededException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
        .body(
            new ErrorResponse()
                .code("RATE_LIMITED")
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now()));
  }
}
