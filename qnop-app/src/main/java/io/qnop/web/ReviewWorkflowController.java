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
import io.qnop.api.v1.model.WorkflowStatus;
import io.qnop.api.v1.model.WorkflowTransitionRequest;
import io.qnop.service.review.ReviewWorkflowService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow transition endpoints (issue #246, ADR-0011): reading a document's workflow status and
 * requesting a transition through the state-machine choke-point. State changes are owner-only; the
 * service enforces that and the domain guards — this controller only maps to the published
 * contract. Domain exceptions are mapped globally by {@link DocumentExceptionHandler}.
 */
@RestController
public class ReviewWorkflowController implements ReviewWorkflowApi {

  private final ReviewWorkflowService workflow;

  public ReviewWorkflowController(ReviewWorkflowService workflow) {
    this.workflow = workflow;
  }

  @Override
  public ResponseEntity<WorkflowStatus> getDocumentWorkflow(UUID documentId) {
    UUID actor = CurrentUser.requireUserId();
    return ResponseEntity.ok(toDto(workflow.status(documentId, actor, CurrentUser.isAdmin())));
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
        .allowedTransitions(status.allowedTransitions());
  }
}
