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
import io.qnop.service.document.DocumentValidationException;
import java.time.OffsetDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link DocumentValidationException} onto the uniform {@code ErrorResponse} envelope (issue
 * #245/#45). A controller advice — rather than per-controller handlers — because the document
 * pipeline spans three controllers (metadata/rendered, multipart upload, binary download) that all
 * raise the same exception.
 */
@RestControllerAdvice
public class DocumentExceptionHandler {

  @ExceptionHandler(DocumentValidationException.class)
  public ResponseEntity<ErrorResponse> onDocumentError(DocumentValidationException ex) {
    return ResponseEntity.status(ex.getStatus())
        .body(
            new ErrorResponse()
                .code(ex.getCode())
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now()));
  }
}
