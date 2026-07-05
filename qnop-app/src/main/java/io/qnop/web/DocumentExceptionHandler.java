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
import io.qnop.service.document.DocumentValidationException;
import io.qnop.service.review.AnnotationActionForbiddenException;
import io.qnop.service.review.AnnotationNotFoundException;
import io.qnop.service.review.DocumentNotFoundException;
import io.qnop.service.review.NotDocumentOwnerException;
import io.qnop.service.review.WorkflowTransitionException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the review-domain exceptions onto the uniform {@code ErrorResponse} envelope (issues
 * #245/#246/#247, #45). A controller advice — rather than per-controller handlers — because the
 * document/review pipeline spans several controllers (metadata/rendered, upload, download,
 * workflow, annotations) that raise the same exceptions.
 */
@RestControllerAdvice
public class DocumentExceptionHandler {

  @ExceptionHandler(DocumentValidationException.class)
  public ResponseEntity<ErrorResponse> onDocumentError(DocumentValidationException ex) {
    ErrorResponse body =
        new ErrorResponse()
            .code(ex.getCode())
            .message(ex.getMessage())
            .timestamp(OffsetDateTime.now());
    if (ex.getField() != null) {
      // Field-scoped rejections (e.g. the slug, issue #411) carry the fieldErrors
      // detail so forms can attach the message to the offending input.
      body.fieldErrors(List.of(new FieldError().field(ex.getField()).message(ex.getMessage())));
    }
    return ResponseEntity.status(ex.getStatus()).body(body);
  }

  @ExceptionHandler(DocumentNotFoundException.class)
  public ResponseEntity<ErrorResponse> onDocumentNotFound(DocumentNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND.value(), "DOCUMENT_NOT_FOUND", ex.getMessage());
  }

  @ExceptionHandler(AnnotationNotFoundException.class)
  public ResponseEntity<ErrorResponse> onAnnotationNotFound(AnnotationNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND.value(), "ANNOTATION_NOT_FOUND", ex.getMessage());
  }

  @ExceptionHandler(NotDocumentOwnerException.class)
  public ResponseEntity<ErrorResponse> onNotOwner(NotDocumentOwnerException ex) {
    return error(HttpStatus.FORBIDDEN.value(), "NOT_DOCUMENT_OWNER", ex.getMessage());
  }

  @ExceptionHandler(AnnotationActionForbiddenException.class)
  public ResponseEntity<ErrorResponse> onAnnotationActionForbidden(
      AnnotationActionForbiddenException ex) {
    return error(HttpStatus.FORBIDDEN.value(), "ANNOTATION_ACTION_FORBIDDEN", ex.getMessage());
  }

  @ExceptionHandler(WorkflowTransitionException.class)
  public ResponseEntity<ErrorResponse> onTransitionRefused(WorkflowTransitionException ex) {
    return error(HttpStatus.CONFLICT.value(), ex.getCode(), ex.getMessage());
  }

  private static ResponseEntity<ErrorResponse> error(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse().code(code).message(message).timestamp(OffsetDateTime.now()));
  }
}
