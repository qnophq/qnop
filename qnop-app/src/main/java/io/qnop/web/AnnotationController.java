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

import io.qnop.api.v1.endpoint.AnnotationsApi;
import io.qnop.api.v1.model.Anchor;
import io.qnop.api.v1.model.AnnotationCreateRequest;
import io.qnop.api.v1.model.AnnotationDecision;
import io.qnop.api.v1.model.AnnotationDecisionRequest;
import io.qnop.api.v1.model.AnnotationListResponse;
import io.qnop.api.v1.model.AnnotationStatus;
import io.qnop.api.v1.model.AnnotationView;
import io.qnop.api.v1.model.CommentCreateRequest;
import io.qnop.api.v1.model.CommentListResponse;
import io.qnop.api.v1.model.CommentView;
import io.qnop.api.v1.model.PlacementStatus;
import io.qnop.service.review.AnnotationService;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Annotations, comment threads and per-version placements (issue #247, ADR-0009/0011), implementing
 * the generated {@link AnnotationsApi} contract. Participant visibility and the owner/author
 * decision rule live in {@link AnnotationService} — this layer maps views to the published models
 * and (de)serializes the jsonb anchor. Domain exceptions are mapped globally by {@link
 * DocumentExceptionHandler}.
 */
@RestController
public class AnnotationController implements AnnotationsApi {

  // Jackson 3 (tools.jackson), matching the stack the document pipeline (de)serializes jsonb with.
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final AnnotationService annotations;

  public AnnotationController(AnnotationService annotations) {
    this.annotations = annotations;
  }

  @Override
  public ResponseEntity<AnnotationView> createAnnotation(
      UUID documentId, AnnotationCreateRequest request) {
    AnnotationService.AnnotationView view =
        annotations.create(
            documentId,
            request.getVersionNumber(),
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin(),
            MAPPER.writeValueAsString(request.getAnchor()),
            request.getComment());
    return ResponseEntity.status(HttpStatus.CREATED).body(toDto(view));
  }

  @Override
  public ResponseEntity<AnnotationListResponse> listAnnotations(
      UUID documentId, Integer version, PlacementStatus placementStatus) {
    var views =
        annotations.list(
            documentId,
            version,
            placementStatus == null ? null : placementStatus.getValue(),
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin());
    return ResponseEntity.ok(
        new AnnotationListResponse().annotations(views.stream().map(this::toDto).toList()));
  }

  @Override
  public ResponseEntity<AnnotationView> getAnnotation(UUID annotationId) {
    return ResponseEntity.ok(
        toDto(annotations.get(annotationId, CurrentUser.requireUserId(), CurrentUser.isAdmin())));
  }

  @Override
  public ResponseEntity<AnnotationView> decideAnnotation(
      UUID annotationId, AnnotationDecisionRequest request) {
    boolean accept = request.getDecision() == AnnotationDecision.ACCEPTED;
    return ResponseEntity.ok(
        toDto(annotations.decide(annotationId, accept, CurrentUser.requireUserId())));
  }

  @Override
  public ResponseEntity<CommentView> addComment(UUID annotationId, CommentCreateRequest request) {
    AnnotationService.CommentView view =
        annotations.addComment(
            annotationId, CurrentUser.requireUserId(), CurrentUser.isAdmin(), request.getBody());
    return ResponseEntity.status(HttpStatus.CREATED).body(toDto(view));
  }

  @Override
  public ResponseEntity<CommentListResponse> listComments(UUID annotationId) {
    var views =
        annotations.listComments(annotationId, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.ok(
        new CommentListResponse().comments(views.stream().map(this::toDto).toList()));
  }

  private AnnotationView toDto(AnnotationService.AnnotationView view) {
    return new AnnotationView()
        .id(view.id())
        .documentId(view.documentId())
        .authorId(view.authorId())
        .status(AnnotationStatus.fromValue(view.status()))
        .anchor(
            view.anchorJson() == null ? null : MAPPER.readValue(view.anchorJson(), Anchor.class))
        .placementStatus(
            view.placementStatus() == null
                ? null
                : PlacementStatus.fromValue(view.placementStatus()))
        .commentCount(view.commentCount())
        .createdAt(view.createdAt().atOffset(ZoneOffset.UTC))
        .updatedAt(view.updatedAt().atOffset(ZoneOffset.UTC));
  }

  private CommentView toDto(AnnotationService.CommentView view) {
    return new CommentView()
        .id(view.id())
        .annotationId(view.annotationId())
        .authorId(view.authorId())
        .body(view.body())
        .createdAt(view.createdAt().atOffset(ZoneOffset.UTC));
  }
}
