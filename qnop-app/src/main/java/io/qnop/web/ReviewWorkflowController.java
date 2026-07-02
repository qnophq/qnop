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

import io.qnop.api.v1.endpoint.ReviewWorkflowApi;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.WorkflowStatus;
import io.qnop.api.v1.model.WorkflowTransitionRequest;
import io.qnop.entity.WorkflowState;
import io.qnop.service.review.DocumentNotFoundException;
import io.qnop.service.review.NotDocumentOwnerException;
import io.qnop.service.review.ReviewWorkflowService;
import io.qnop.service.review.WorkflowTransitionException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow transition endpoints (issue #246, ADR-0011): reading a document's workflow status and
 * requesting a transition through the state-machine choke-point. State changes are owner-only; the
 * service enforces that and the domain guards — this controller only maps to the published
 * contract.
 */
@RestController
public class ReviewWorkflowController implements ReviewWorkflowApi {

  private final ReviewWorkflowService workflow;

  public ReviewWorkflowController(ReviewWorkflowService workflow) {
    this.workflow = workflow;
  }

  @Override
  public ResponseEntity<WorkflowStatus> getDocumentWorkflow(UUID documentId) {
    return ResponseEntity.ok(toDto(workflow.status(documentId)));
  }

  @Override
  public ResponseEntity<WorkflowStatus> transitionDocumentWorkflow(
      UUID documentId, WorkflowTransitionRequest request) {
    UUID actor = CurrentUser.requireUserId();
    return ResponseEntity.ok(
        toDto(workflow.transition(documentId, request.getTargetState(), actor)));
  }

  private static WorkflowStatus toDto(ReviewWorkflowService.WorkflowStatus status) {
    return new WorkflowStatus()
        .state(status.state())
        .allowedTransitions(status.allowedTransitions().stream().map(WorkflowState::name).toList());
  }

  @ExceptionHandler(DocumentNotFoundException.class)
  ResponseEntity<ErrorResponse> documentNotFound(DocumentNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", ex.getMessage());
  }

  @ExceptionHandler(NotDocumentOwnerException.class)
  ResponseEntity<ErrorResponse> notOwner(NotDocumentOwnerException ex) {
    return error(HttpStatus.FORBIDDEN, "NOT_DOCUMENT_OWNER", ex.getMessage());
  }

  @ExceptionHandler(WorkflowTransitionException.class)
  ResponseEntity<ErrorResponse> transitionRefused(WorkflowTransitionException ex) {
    return error(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage());
  }

  private static ResponseEntity<ErrorResponse> error(
      HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(
            new ErrorResponse()
                .code(code)
                .message(message)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC)));
  }
}
