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

import static java.util.stream.Collectors.toMap;

import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.AnnotationPriority;
import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.AnnotationType;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Comment;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ThreadParticipation;
import io.qnop.repository.AnnotationCommentActivity;
import io.qnop.repository.AnnotationCommentCount;
import io.qnop.repository.AnnotationFirstComment;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.document.DocumentValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Annotations, their comment threads, and per-version placements (issue #247, ADR-0009/0011).
 *
 * <p>A participant creates an anchored annotation on a version they are viewing; the placement on
 * that version is {@code PLACED} immediately (a human drew it — re-anchoring onto later versions is
 * the async job of #248). Resolving an annotation (author-only, #405) is the workflow's concern and
 * lives in {@link ReviewWorkflowService}. Visibility is delegated to {@link DocumentAccessService},
 * so a non-participant sees the same 404 as for the document itself (anti-enumeration).
 */
@Service
public class AnnotationService {

  static final String AUDIT_ANNOTATION_CREATED = "annotation.created";
  static final String AUDIT_ANNOTATION_CLASSIFIED = "annotation.classified";

  /** Comments can be 20k chars; the view carries only what a card title needs (issue #393). */
  static final int FIRST_COMMENT_EXCERPT_CHARS = 300;

  private final AnnotationRepository annotations;
  private final AnnotationPlacementRepository placements;
  private final CommentRepository comments;
  private final DocumentVersionRepository versions;
  private final AuditEventRepository auditEvents;
  private final DocumentAccessService documentAccess;
  private final ReviewWorkflowService workflow;
  private final ReviewIdentityResolver identity;
  private final ReactionService reactions_;

  public AnnotationService(
      AnnotationRepository annotations,
      AnnotationPlacementRepository placements,
      CommentRepository comments,
      DocumentVersionRepository versions,
      AuditEventRepository auditEvents,
      DocumentAccessService documentAccess,
      ReviewWorkflowService workflow,
      ReviewIdentityResolver identity,
      ReactionService reactions) {
    this.annotations = annotations;
    this.placements = placements;
    this.comments = comments;
    this.versions = versions;
    this.auditEvents = auditEvents;
    this.documentAccess = documentAccess;
    this.workflow = workflow;
    this.identity = identity;
    this.reactions_ = reactions;
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
      String authorDisplayName,
      String status,
      String type,
      String priority,
      String anchorJson,
      String placementStatus,
      String firstComment,
      int commentCount,
      Instant latestCommentFromOthersAt,
      List<ReactionService.ReactionGroup> reactions,
      Instant createdAt,
      Instant updatedAt) {}

  /** One message in an annotation's thread. */
  public record CommentView(
      UUID id,
      UUID annotationId,
      UUID authorId,
      String authorDisplayName,
      String body,
      List<ReactionService.ReactionGroup> reactions,
      Instant createdAt) {}

  /**
   * Creates an annotation on {@code versionNumber}, seeded with its mandatory first comment — an
   * annotation without text must not exist (issue #301). With an {@code anchorJson} it is placed
   * immediately; without one it is document-scoped (issue #395) — no placement, valid on every
   * version. Only the LATEST version accepts new annotations (issue #306) — older versions are a
   * read-only record; the guard answers 409 {@code VERSION_READ_ONLY} so a client racing a
   * concurrent re-upload gets a stable, mappable signal.
   */
  @Transactional
  public AnnotationView create(
      UUID documentId,
      int versionNumber,
      UUID author,
      boolean admin,
      String anchorJson,
      String firstComment,
      String type,
      String priority) {
    if (firstComment == null || firstComment.isBlank()) {
      throw DocumentValidationException.invalidRequest("annotation requires a first comment");
    }
    documentAccess.getDocument(documentId, author, admin); // visibility → 404 if not a participant
    DocumentVersion version =
        versions
            .findByDocumentIdAndVersionNumber(documentId, versionNumber)
            .orElseThrow(
                () -> DocumentValidationException.notFound("no such version: " + versionNumber));
    int latest =
        versions
            .findTopByDocumentIdOrderByVersionNumberDesc(documentId)
            .map(DocumentVersion::getVersionNumber)
            .orElse(versionNumber);
    if (versionNumber != latest) {
      throw DocumentValidationException.versionReadOnly(
          "annotations are created on the latest version (v%d), not v%d"
              .formatted(latest, versionNumber));
    }

    // saveAndFlush so the @CreationTimestamp / @UpdateTimestamp are populated on the returned
    // entity (they are only set when the INSERT is flushed) before the view is built.
    Annotation created = new Annotation(documentId, author);
    created.classify(typeOf(type), priorityOf(priority));
    Annotation annotation = annotations.saveAndFlush(created);
    // A located annotation is placed immediately on the version it was drawn on. A document-scoped
    // annotation (issue #395) carries no anchor and gets no placement at all — it stays valid on
    // every version, never re-anchored and never orphaned (ADR-0009). The null placement flows
    // through to a view with a null anchor/placementStatus, the client's document-scope signal.
    AnnotationPlacement placement = null;
    if (anchorJson != null) {
      placement = new AnnotationPlacement(annotation.getId(), version.getId(), anchorJson);
      placement.markPlaced(anchorJson);
      placements.save(placement);
    }

    comments.save(new Comment(annotation.getId(), author, firstComment));
    int commentCount = 1;
    auditEvents.save(
        new AuditEvent(
            documentId,
            AUDIT_ANNOTATION_CREATED,
            author,
            "{\"annotationId\":\"" + annotation.getId() + "\"}"));
    // The workflow reacts to the raise (issue #405): a closed review rolls this insert back
    // (REVIEW_CLOSED), an IN_REVIEW one derives CHANGES_REQUESTED — both under the document lock.
    workflow.annotationRaised(documentId, author);
    return view(
        annotation,
        placement,
        excerpt(firstComment),
        commentCount,
        null,
        List.of(),
        identity.forDocument(documentId, author));
  }

  /**
   * All of a document's annotations; each with its placement on {@code versionNumber} when given.
   * {@code placementStatus} (requires {@code versionNumber}) narrows to placements in that state —
   * e.g. ORPHANED to surface what re-anchoring could not relocate (issue #248).
   */
  @Transactional(readOnly = true)
  public List<AnnotationView> list(
      UUID documentId,
      Integer versionNumber,
      String placementStatus,
      String type,
      UUID actor,
      boolean admin) {
    if (placementStatus != null && versionNumber == null) {
      throw DocumentValidationException.invalidRequest("placementStatus requires version");
    }
    DocumentAccessService.DocumentView document =
        documentAccess.getDocument(documentId, actor, admin); // 404 if not a participant
    UUID versionId =
        versionNumber == null
            ? null
            : versions
                .findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .map(DocumentVersion::getId)
                .orElse(null);
    // Under a PRIVATE policy (issue #413) foreign threads are dropped here, so the
    // batched counts and the FE-derived progress follow the caller's visibility.
    List<Annotation> documentAnnotations =
        annotations.findByDocumentId(documentId).stream()
            .filter(annotation -> canSeeThread(document, annotation.getAuthorId(), actor, admin))
            .toList();
    // Batch the placements and comment counts (issue #313): one query each instead of 2N.
    Map<UUID, AnnotationPlacement> placementByAnnotation =
        versionId == null
            ? Map.of()
            : placements.findByDocumentVersionId(versionId).stream()
                .collect(toMap(AnnotationPlacement::getAnnotationId, placement -> placement));
    Map<UUID, Integer> threadSizeByAnnotation = threadSizes(documentAnnotations);
    Map<UUID, String> firstCommentByAnnotation = firstComments(documentAnnotations);
    Map<UUID, Instant> foreignActivityByAnnotation = foreignActivity(documentAnnotations, actor);
    ReviewIdentityResolver.ReviewIdentities identities = identity.forDocument(documentId, actor);
    // Reaction chips, batched like the counts (issue #410).
    Map<UUID, List<ReactionService.ReactionGroup>> reactionsByAnnotation =
        reactions_.forAnnotations(
            documentAnnotations.stream().map(Annotation::getId).toList(), actor, identities);
    return documentAnnotations.stream()
        .map(
            annotation ->
                view(
                    annotation,
                    placementByAnnotation.get(annotation.getId()),
                    firstCommentByAnnotation.get(annotation.getId()),
                    threadSizeByAnnotation.getOrDefault(annotation.getId(), 0),
                    foreignActivityByAnnotation.get(annotation.getId()),
                    reactionsByAnnotation.getOrDefault(annotation.getId(), List.of()),
                    identities))
        .filter(view -> placementStatus == null || placementStatus.equals(view.placementStatus()))
        .filter(view -> type == null || type.equals(view.type()))
        .toList();
  }

  /**
   * (Re)classifies an annotation — the request replaces the classification wholesale, an absent
   * facet clears it (issue #392). Permitted for the document owner or the annotation's author while
   * the annotation is still OPEN. Classification is descriptive only, so it lives here and never
   * drives the workflow.
   */
  @Transactional
  public AnnotationView updateClassification(
      UUID annotationId, UUID actor, boolean admin, String type, String priority) {
    Annotation annotation = requireAnnotation(annotationId);
    DocumentAccessService.DocumentView document =
        documentAccess.getDocument(annotation.getDocumentId(), actor, admin);
    if (!document.ownerId().equals(actor) && !annotation.getAuthorId().equals(actor)) {
      throw new AnnotationActionForbiddenException(
          "Only the document owner or the annotation's author may classify it");
    }
    if (annotation.getStatus() != AnnotationStatus.OPEN) {
      throw new WorkflowTransitionException(
          WorkflowTransitionException.ANNOTATION_ALREADY_RESOLVED,
          "annotation " + annotationId + " is already " + annotation.getStatus());
    }
    annotation.classify(typeOf(type), priorityOf(priority));
    auditEvents.save(
        new AuditEvent(
            annotation.getDocumentId(),
            AUDIT_ANNOTATION_CLASSIFIED,
            actor,
            "{\"annotationId\":\"%s\",\"type\":%s,\"priority\":%s}"
                .formatted(annotationId, jsonString(type), jsonString(priority))));
    ReviewIdentityResolver.ReviewIdentities identities =
        identity.forDocument(annotation.getDocumentId(), actor);
    return view(
        annotation,
        null,
        firstComment(annotationId),
        threadSize(annotationId),
        foreignActivity(annotationId, actor),
        annotationReactions(annotationId, actor, identities),
        identities);
  }

  private static String jsonString(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  /** The entity enum for a contract-validated name; the closed set is enforced by the contract. */
  private static AnnotationType typeOf(String type) {
    return type == null ? null : AnnotationType.valueOf(type);
  }

  private static AnnotationPriority priorityOf(String priority) {
    return priority == null ? null : AnnotationPriority.valueOf(priority);
  }

  /** A single annotation (no version context, so no placement), visible to participants. */
  @Transactional(readOnly = true)
  public AnnotationView get(UUID annotationId, UUID actor, boolean admin) {
    Annotation annotation = requireAnnotation(annotationId);
    DocumentAccessService.DocumentView document =
        documentAccess.getDocument(annotation.getDocumentId(), actor, admin);
    if (!canSeeThread(document, annotation.getAuthorId(), actor, admin)) {
      // A PRIVATE foreign thread is invisible — 404, like the document itself.
      throw DocumentValidationException.notFound("no such annotation: " + annotationId);
    }
    ReviewIdentityResolver.ReviewIdentities identities =
        identity.forDocument(annotation.getDocumentId(), actor);
    return view(
        annotation,
        null,
        firstComment(annotationId),
        threadSize(annotationId),
        foreignActivity(annotationId, actor),
        annotationReactions(annotationId, actor, identities),
        identities);
  }

  /**
   * Resolves the annotation as its author (ADR-0011 as amended by #405) via the workflow
   * choke-point (which enforces the authorization, appends the optional closing note to the thread
   * and re-derives the document state), and returns the updated annotation's view.
   */
  @Transactional
  public AnnotationView resolve(UUID annotationId, String note, UUID actor) {
    Annotation updated = workflow.resolveAnnotation(annotationId, note, actor);
    ReviewIdentityResolver.ReviewIdentities identities =
        identity.forDocument(updated.getDocumentId(), actor);
    return view(
        updated,
        null,
        firstComment(updated.getId()),
        threadSize(updated.getId()),
        foreignActivity(updated.getId(), actor),
        annotationReactions(updated.getId(), actor, identities),
        identities);
  }

  /**
   * Reopens the annotation as its author (issue #394) via the workflow choke-point (which enforces
   * the authorization, the closed-review guard and the state re-derivation), and returns the
   * updated annotation's view.
   */
  @Transactional
  public AnnotationView reopen(UUID annotationId, UUID actor) {
    Annotation updated = workflow.reopenAnnotation(annotationId, actor);
    ReviewIdentityResolver.ReviewIdentities identities =
        identity.forDocument(updated.getDocumentId(), actor);
    return view(
        updated,
        null,
        firstComment(updated.getId()),
        threadSize(updated.getId()),
        foreignActivity(updated.getId(), actor),
        annotationReactions(updated.getId(), actor, identities),
        identities);
  }

  /**
   * Appends a comment to an annotation's thread; visible participants only. A RESOLVED annotation's
   * thread is a closed record (issue #403) — the author settled the concern, so further comments
   * are refused with 409 (the resolve note itself is written by {@link
   * ReviewWorkflowService#resolveAnnotation} before the status flips).
   */
  @Transactional
  public CommentView addComment(UUID annotationId, UUID author, boolean admin, String body) {
    Annotation annotation = requireAnnotation(annotationId);
    DocumentAccessService.DocumentView document =
        documentAccess.getDocument(annotation.getDocumentId(), author, admin);
    if (!canSeeThread(document, annotation.getAuthorId(), author, admin)) {
      // Invisible under PRIVATE — same 404 as the read paths (anti-enumeration).
      throw DocumentValidationException.notFound("no such annotation: " + annotationId);
    }
    if (!canWriteThread(document, annotation.getAuthorId(), author, admin)) {
      // READ_ONLY (issue #413): the thread is visible, but only the author and owner may comment.
      throw DocumentValidationException.threadReadOnly(
          "this review's threads accept comments only from the author and the owner");
    }
    if (annotation.getStatus() != AnnotationStatus.OPEN) {
      throw new WorkflowTransitionException(
          WorkflowTransitionException.ANNOTATION_ALREADY_RESOLVED,
          "annotation "
              + annotationId
              + " is "
              + annotation.getStatus()
              + "; its thread is closed");
    }
    // saveAndFlush so @CreationTimestamp is populated on the returned comment before the view.
    Comment saved = comments.saveAndFlush(new Comment(annotationId, author, body));
    return commentView(saved, List.of(), identity.forDocument(annotation.getDocumentId(), author));
  }

  /** An annotation's comment thread, oldest first; visible participants only. */
  @Transactional(readOnly = true)
  public List<CommentView> listComments(UUID annotationId, UUID actor, boolean admin) {
    Annotation annotation = requireAnnotation(annotationId);
    DocumentAccessService.DocumentView document =
        documentAccess.getDocument(annotation.getDocumentId(), actor, admin);
    if (!canSeeThread(document, annotation.getAuthorId(), actor, admin)) {
      throw DocumentValidationException.notFound("no such annotation: " + annotationId);
    }
    ReviewIdentityResolver.ReviewIdentities identities =
        identity.forDocument(annotation.getDocumentId(), actor);
    List<Comment> thread = comments.findByAnnotationIdOrderByCreatedAtAsc(annotationId);
    // Reaction chips per bubble, batched (issue #410).
    Map<UUID, List<ReactionService.ReactionGroup>> reactionsByComment =
        reactions_.forComments(thread.stream().map(Comment::getId).toList(), actor, identities);
    return thread.stream()
        .map(
            comment ->
                commentView(
                    comment,
                    reactionsByComment.getOrDefault(comment.getId(), List.of()),
                    identities))
        .toList();
  }

  /** Reaction chips of one annotation (list uses the batched variant, issue #410). */
  private List<ReactionService.ReactionGroup> annotationReactions(
      UUID annotationId, UUID actor, ReviewIdentityResolver.ReviewIdentities identities) {
    return reactions_
        .forAnnotations(List.of(annotationId), actor, identities)
        .getOrDefault(annotationId, List.of());
  }

  private Annotation requireAnnotation(UUID annotationId) {
    return annotations
        .findById(annotationId)
        .orElseThrow(() -> new AnnotationNotFoundException(annotationId));
  }

  private int threadSize(UUID annotationId) {
    return (int) comments.countByAnnotationId(annotationId);
  }

  /** Thread sizes for a batch of annotations in a single aggregation (issue #313). */
  private Map<UUID, Integer> threadSizes(List<Annotation> forAnnotations) {
    if (forAnnotations.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = forAnnotations.stream().map(Annotation::getId).toList();
    return comments.countByAnnotationIdIn(ids).stream()
        .collect(toMap(AnnotationCommentCount::annotationId, count -> (int) count.count()));
  }

  /** The opening comment's excerpt for one annotation (list uses the batched variant). */
  private String firstComment(UUID annotationId) {
    return comments
        .findFirstByAnnotationIdOrderByCreatedAtAscIdAsc(annotationId)
        .map(comment -> excerpt(comment.getBody()))
        .orElse(null);
  }

  /** Opening-comment excerpts for a batch of annotations in a single query (issue #393). */
  private Map<UUID, String> firstComments(List<Annotation> forAnnotations) {
    if (forAnnotations.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = forAnnotations.stream().map(Annotation::getId).toList();
    return comments.findFirstByAnnotationIdIn(ids).stream()
        .collect(toMap(AnnotationFirstComment::getAnnotationId, first -> excerpt(first.getBody())));
  }

  /** The newest foreign comment time for one annotation (list uses the batched variant). */
  private Instant foreignActivity(UUID annotationId, UUID viewer) {
    return comments
        .findFirstByAnnotationIdAndAuthorIdNotOrderByCreatedAtDesc(annotationId, viewer)
        .map(Comment::getCreatedAt)
        .orElse(null);
  }

  /** Newest foreign comment times for a batch of annotations in a single query (issue #307). */
  private Map<UUID, Instant> foreignActivity(List<Annotation> forAnnotations, UUID viewer) {
    if (forAnnotations.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = forAnnotations.stream().map(Annotation::getId).toList();
    return comments.latestForeignActivityByAnnotationIdIn(ids, viewer).stream()
        .collect(
            toMap(AnnotationCommentActivity::annotationId, AnnotationCommentActivity::latestAt));
  }

  private static String excerpt(String body) {
    return body.length() <= FIRST_COMMENT_EXCERPT_CHARS
        ? body
        : body.substring(0, FIRST_COMMENT_EXCERPT_CHARS);
  }

  /**
   * Whether {@code actor} may SEE this annotation's thread under the review's participation policy
   * (issue #413). Only PRIVATE hides a foreign thread (a caller who is neither its author nor the
   * owner); READ_ONLY and OPEN show every thread. Admins see everything.
   */
  static boolean canSeeThread(
      DocumentAccessService.DocumentView document, UUID authorId, UUID actor, boolean admin) {
    if (!ThreadParticipation.PRIVATE.name().equals(document.threadParticipation())) {
      return true;
    }
    return admin || actor.equals(document.ownerId()) || actor.equals(authorId);
  }

  /**
   * Whether {@code actor} may COMMENT in this annotation's thread (issue #413). OPEN lets every
   * participant write; PRIVATE and READ_ONLY restrict writing to the author and the owner (a
   * foreign PRIVATE thread is already invisible, so this only bites READ_ONLY). Admins may always
   * write.
   */
  private static boolean canWriteThread(
      DocumentAccessService.DocumentView document, UUID authorId, UUID actor, boolean admin) {
    if (ThreadParticipation.OPEN.name().equals(document.threadParticipation())) {
      return true;
    }
    return admin || actor.equals(document.ownerId()) || actor.equals(authorId);
  }

  private static AnnotationView view(
      Annotation annotation,
      AnnotationPlacement placement,
      String firstComment,
      int commentCount,
      Instant latestCommentFromOthersAt,
      List<ReactionService.ReactionGroup> reactions,
      ReviewIdentityResolver.ReviewIdentities identities) {
    UUID authorId = annotation.getAuthorId();
    return new AnnotationView(
        annotation.getId(),
        annotation.getDocumentId(),
        identities.exposedAuthorId(authorId),
        identities.displayName(authorId),
        annotation.getStatus().name(),
        annotation.getType() == null ? null : annotation.getType().name(),
        annotation.getPriority() == null ? null : annotation.getPriority().name(),
        placement == null ? null : placement.getAnchor(),
        placement == null ? null : placement.getStatus().name(),
        firstComment,
        commentCount,
        latestCommentFromOthersAt,
        reactions,
        annotation.getCreatedAt(),
        annotation.getUpdatedAt());
  }

  private static CommentView commentView(
      Comment comment,
      List<ReactionService.ReactionGroup> reactions,
      ReviewIdentityResolver.ReviewIdentities identities) {
    UUID authorId = comment.getAuthorId();
    return new CommentView(
        comment.getId(),
        comment.getAnnotationId(),
        identities.exposedAuthorId(authorId),
        identities.displayName(authorId),
        comment.getBody(),
        reactions,
        comment.getCreatedAt());
  }
}
