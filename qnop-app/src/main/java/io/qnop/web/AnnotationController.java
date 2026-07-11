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
import io.qnop.api.v1.model.AnnotationClassificationRequest;
import io.qnop.api.v1.model.AnnotationCreateRequest;
import io.qnop.api.v1.model.AnnotationListResponse;
import io.qnop.api.v1.model.AnnotationPriority;
import io.qnop.api.v1.model.AnnotationResolveRequest;
import io.qnop.api.v1.model.AnnotationStatus;
import io.qnop.api.v1.model.AnnotationType;
import io.qnop.api.v1.model.AnnotationView;
import io.qnop.api.v1.model.CommentCreateRequest;
import io.qnop.api.v1.model.CommentListResponse;
import io.qnop.api.v1.model.CommentView;
import io.qnop.api.v1.model.PlacementStatus;
import io.qnop.api.v1.model.ReactionGroup;
import io.qnop.service.review.AnnotationService;
import io.qnop.service.review.ReactionService;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Annotations, comment threads and per-version placements (issue #247, ADR-0009/0011), implementing
 * the generated {@link AnnotationsApi} contract. Participant visibility and the author-only resolve
 * rule (issue #405) live in {@link AnnotationService} — this layer maps views to the published
 * models and (de)serializes the jsonb anchor. Domain exceptions are mapped globally by {@link
 * DocumentExceptionHandler}.
 */
@RestController
public class AnnotationController implements AnnotationsApi {

  private final AnnotationService annotations;
  private final ReactionService reactions;

  /**
   * The framework-configured Jackson 3 mapper (issue #329): reusing Boot's MVC {@link ObjectMapper}
   * — rather than a hand-built one — keeps the jsonb anchor round-trip byte-for-byte consistent
   * with the HTTP (de)serialization of the same published models and inherits Boot's lenient
   * defaults (e.g. tolerating unknown properties on a stored anchor).
   */
  private final ObjectMapper mapper;

  public AnnotationController(
      AnnotationService annotations, ReactionService reactions, ObjectMapper mapper) {
    this.annotations = annotations;
    this.reactions = reactions;
    this.mapper = mapper;
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
            // A document-scoped annotation (issue #395) carries no anchor; keep it a Java null
            // rather than the JSON literal "null" so the service creates no placement for it.
            request.getAnchor() == null ? null : mapper.writeValueAsString(request.getAnchor()),
            request.getComment(),
            request.getType() == null ? null : request.getType().getValue(),
            request.getPriority() == null ? null : request.getPriority().getValue());
    return ResponseEntity.status(HttpStatus.CREATED).body(toDto(view));
  }

  @Override
  public ResponseEntity<AnnotationListResponse> listAnnotations(
      UUID documentId, Integer version, PlacementStatus placementStatus, AnnotationType type) {
    var views =
        annotations.list(
            documentId,
            version,
            placementStatus == null ? null : placementStatus.getValue(),
            type == null ? null : type.getValue(),
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin());
    return ResponseEntity.ok(
        new AnnotationListResponse().annotations(views.stream().map(this::toDto).toList()));
  }

  @Override
  public ResponseEntity<AnnotationView> classifyAnnotation(
      UUID annotationId, AnnotationClassificationRequest request) {
    AnnotationService.AnnotationView view =
        annotations.updateClassification(
            annotationId,
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin(),
            request.getType() == null ? null : request.getType().getValue(),
            request.getPriority() == null ? null : request.getPriority().getValue());
    return ResponseEntity.ok(toDto(view));
  }

  @Override
  public ResponseEntity<AnnotationView> getAnnotation(UUID annotationId) {
    return ResponseEntity.ok(
        toDto(annotations.get(annotationId, CurrentUser.requireUserId(), CurrentUser.isAdmin())));
  }

  @Override
  public ResponseEntity<AnnotationView> reopenAnnotation(UUID annotationId) {
    return ResponseEntity.ok(toDto(annotations.reopen(annotationId, CurrentUser.requireUserId())));
  }

  @Override
  public ResponseEntity<AnnotationView> resolveAnnotation(
      UUID annotationId, AnnotationResolveRequest request) {
    String note = request == null ? null : request.getNote();
    return ResponseEntity.ok(
        toDto(annotations.resolve(annotationId, note, CurrentUser.requireUserId())));
  }

  @Override
  public ResponseEntity<AnnotationView> confirmPlacement(UUID annotationId, Integer versionNumber) {
    return ResponseEntity.ok(
        toDto(
            annotations.confirmPlacement(
                annotationId, versionNumber, CurrentUser.requireUserId(), CurrentUser.isAdmin())));
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

  @Override
  public ResponseEntity<Void> reactToAnnotation(UUID annotationId, String emoji) {
    reactions.reactToAnnotation(
        annotationId, emoji, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> unreactFromAnnotation(UUID annotationId, String emoji) {
    reactions.unreactFromAnnotation(
        annotationId, emoji, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> reactToComment(UUID commentId, String emoji) {
    reactions.reactToComment(commentId, emoji, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> unreactFromComment(UUID commentId, String emoji) {
    reactions.unreactFromComment(
        commentId, emoji, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.noContent().build();
  }

  private static java.util.List<ReactionGroup> toReactionDtos(
      java.util.List<ReactionService.ReactionGroup> groups) {
    return groups.stream()
        .map(
            group ->
                new ReactionGroup()
                    .emoji(group.emoji())
                    .count(group.count())
                    .reactedByMe(group.reactedByMe())
                    .reactors(group.reactors()))
        .toList();
  }

  private AnnotationView toDto(AnnotationService.AnnotationView view) {
    return new AnnotationView()
        .id(view.id())
        .documentId(view.documentId())
        .authorId(view.authorId())
        .authorDisplayName(view.authorDisplayName())
        .status(AnnotationStatus.fromValue(view.status()))
        .type(view.type() == null ? null : AnnotationType.fromValue(view.type()))
        .priority(view.priority() == null ? null : AnnotationPriority.fromValue(view.priority()))
        .anchor(
            view.anchorJson() == null ? null : mapper.readValue(view.anchorJson(), Anchor.class))
        .placementStatus(
            view.placementStatus() == null
                ? null
                : PlacementStatus.fromValue(view.placementStatus()))
        .firstComment(view.firstComment())
        .commentCount(view.commentCount())
        .reactions(toReactionDtos(view.reactions()))
        .latestCommentFromOthersAt(
            view.latestCommentFromOthersAt() == null
                ? null
                : view.latestCommentFromOthersAt().atOffset(ZoneOffset.UTC))
        .createdAt(view.createdAt().atOffset(ZoneOffset.UTC))
        .updatedAt(view.updatedAt().atOffset(ZoneOffset.UTC));
  }

  private CommentView toDto(AnnotationService.CommentView view) {
    return new CommentView()
        .id(view.id())
        .annotationId(view.annotationId())
        .authorId(view.authorId())
        .authorDisplayName(view.authorDisplayName())
        .body(view.body())
        .reactions(toReactionDtos(view.reactions()))
        .createdAt(view.createdAt().atOffset(ZoneOffset.UTC));
  }
}
