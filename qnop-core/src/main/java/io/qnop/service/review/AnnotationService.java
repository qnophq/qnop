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
package io.qnop.service.review;

import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Comment;
import io.qnop.entity.DocumentVersion;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.document.DocumentValidationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Annotations, their comment threads, and per-version placements (issue #247, ADR-0009/0011).
 *
 * <p>A participant creates an anchored annotation on a version they are viewing; the placement on
 * that version is {@code PLACED} immediately (a human drew it — re-anchoring onto later versions is
 * the async job of #248). The owner/author decision (accept/reject) is the workflow's concern and
 * lives in {@link ReviewWorkflowService}. Visibility is delegated to {@link DocumentAccessService},
 * so a non-participant sees the same 404 as for the document itself (anti-enumeration).
 */
@Service
public class AnnotationService {

  static final String AUDIT_ANNOTATION_CREATED = "annotation.created";

  private final AnnotationRepository annotations;
  private final AnnotationPlacementRepository placements;
  private final CommentRepository comments;
  private final DocumentVersionRepository versions;
  private final AuditEventRepository auditEvents;
  private final DocumentAccessService documentAccess;
  private final ReviewWorkflowService workflow;

  public AnnotationService(
      AnnotationRepository annotations,
      AnnotationPlacementRepository placements,
      CommentRepository comments,
      DocumentVersionRepository versions,
      AuditEventRepository auditEvents,
      DocumentAccessService documentAccess,
      ReviewWorkflowService workflow) {
    this.annotations = annotations;
    this.placements = placements;
    this.comments = comments;
    this.versions = versions;
    this.auditEvents = auditEvents;
    this.documentAccess = documentAccess;
    this.workflow = workflow;
  }

  /**
   * An annotation with (when a version was requested) its placement there and its thread size.
   * {@code status} / {@code placementStatus} are the enum names (kept as strings so the web layer
   * maps them without depending on the entity enums, ADR-0004).
   */
  public record AnnotationView(
      UUID id,
      UUID documentId,
      UUID authorId,
      String status,
      String anchorJson,
      String placementStatus,
      int commentCount,
      Instant createdAt,
      Instant updatedAt) {}

  /** One message in an annotation's thread. */
  public record CommentView(
      UUID id, UUID annotationId, UUID authorId, String body, Instant createdAt) {}

  /**
   * Creates an annotation on {@code versionNumber}, placed immediately, seeded with its mandatory
   * first comment — an annotation without text must not exist (issue #301).
   */
  @Transactional
  public AnnotationView create(
      UUID documentId,
      int versionNumber,
      UUID author,
      boolean admin,
      String anchorJson,
      String firstComment) {
    if (firstComment == null || firstComment.isBlank()) {
      throw DocumentValidationException.invalidRequest("annotation requires a first comment");
    }
    documentAccess.getDocument(documentId, author, admin); // visibility → 404 if not a participant
    DocumentVersion version =
        versions
            .findByDocumentIdAndVersionNumber(documentId, versionNumber)
            .orElseThrow(
                () -> DocumentValidationException.notFound("no such version: " + versionNumber));

    // saveAndFlush so the @CreationTimestamp / @UpdateTimestamp are populated on the returned
    // entity (they are only set when the INSERT is flushed) before the view is built.
    Annotation annotation = annotations.saveAndFlush(new Annotation(documentId, author));
    AnnotationPlacement placement =
        new AnnotationPlacement(annotation.getId(), version.getId(), anchorJson);
    placement.markPlaced(anchorJson);
    placements.save(placement);

    comments.save(new Comment(annotation.getId(), author, firstComment));
    int commentCount = 1;
    auditEvents.save(
        new AuditEvent(
            documentId,
            AUDIT_ANNOTATION_CREATED,
            author,
            "{\"annotationId\":\"" + annotation.getId() + "\"}"));
    return view(annotation, placement, commentCount);
  }

  /**
   * All of a document's annotations; each with its placement on {@code versionNumber} when given.
   * {@code placementStatus} (requires {@code versionNumber}) narrows to placements in that state —
   * e.g. ORPHANED to surface what re-anchoring could not relocate (issue #248).
   */
  @Transactional(readOnly = true)
  public List<AnnotationView> list(
      UUID documentId, Integer versionNumber, String placementStatus, UUID actor, boolean admin) {
    if (placementStatus != null && versionNumber == null) {
      throw DocumentValidationException.invalidRequest("placementStatus requires version");
    }
    documentAccess.getDocument(documentId, actor, admin); // visibility → 404 if not a participant
    UUID versionId =
        versionNumber == null
            ? null
            : versions
                .findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .map(DocumentVersion::getId)
                .orElse(null);
    return annotations.findByDocumentId(documentId).stream()
        .map(
            annotation -> {
              AnnotationPlacement placement =
                  versionId == null
                      ? null
                      : placements
                          .findByAnnotationIdAndDocumentVersionId(annotation.getId(), versionId)
                          .orElse(null);
              return view(annotation, placement, threadSize(annotation.getId()));
            })
        .filter(view -> placementStatus == null || placementStatus.equals(view.placementStatus()))
        .toList();
  }

  /** A single annotation (no version context, so no placement), visible to participants. */
  @Transactional(readOnly = true)
  public AnnotationView get(UUID annotationId, UUID actor, boolean admin) {
    Annotation annotation = requireAnnotation(annotationId);
    documentAccess.getDocument(annotation.getDocumentId(), actor, admin);
    return view(annotation, null, threadSize(annotationId));
  }

  /**
   * Applies the owner/author decision (ADR-0011) via the workflow choke-point (which enforces the
   * authorization and drives the document state), and returns the updated annotation's view.
   */
  @Transactional
  public AnnotationView decide(UUID annotationId, boolean accept, UUID actor) {
    AnnotationStatus decision = accept ? AnnotationStatus.ACCEPTED : AnnotationStatus.REJECTED;
    Annotation updated = workflow.decideAnnotation(annotationId, decision, actor);
    return view(updated, null, threadSize(updated.getId()));
  }

  /** Appends a comment to an annotation's thread; visible participants only. */
  @Transactional
  public CommentView addComment(UUID annotationId, UUID author, boolean admin, String body) {
    Annotation annotation = requireAnnotation(annotationId);
    documentAccess.getDocument(annotation.getDocumentId(), author, admin);
    // saveAndFlush so @CreationTimestamp is populated on the returned comment before the view.
    return commentView(comments.saveAndFlush(new Comment(annotationId, author, body)));
  }

  /** An annotation's comment thread, oldest first; visible participants only. */
  @Transactional(readOnly = true)
  public List<CommentView> listComments(UUID annotationId, UUID actor, boolean admin) {
    Annotation annotation = requireAnnotation(annotationId);
    documentAccess.getDocument(annotation.getDocumentId(), actor, admin);
    return comments.findByAnnotationIdOrderByCreatedAtAsc(annotationId).stream()
        .map(AnnotationService::commentView)
        .toList();
  }

  private Annotation requireAnnotation(UUID annotationId) {
    return annotations
        .findById(annotationId)
        .orElseThrow(() -> new AnnotationNotFoundException(annotationId));
  }

  private int threadSize(UUID annotationId) {
    return (int) comments.countByAnnotationId(annotationId);
  }

  private static AnnotationView view(
      Annotation annotation, AnnotationPlacement placement, int commentCount) {
    return new AnnotationView(
        annotation.getId(),
        annotation.getDocumentId(),
        annotation.getAuthorId(),
        annotation.getStatus().name(),
        placement == null ? null : placement.getAnchor(),
        placement == null ? null : placement.getStatus().name(),
        commentCount,
        annotation.getCreatedAt(),
        annotation.getUpdatedAt());
  }

  private static CommentView commentView(Comment comment) {
    return new CommentView(
        comment.getId(),
        comment.getAnnotationId(),
        comment.getAuthorId(),
        comment.getBody(),
        comment.getCreatedAt());
  }
}
