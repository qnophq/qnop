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
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Comment;
import io.qnop.entity.Document;
import io.qnop.entity.ThreadParticipation;
import io.qnop.repository.AnnotationFirstComment;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dashboard's cross-review aggregates (issue #454): replies directed at the caller, the recent
 * activity of their reviews, and the weekly resolved count. Everything the per-review cards need
 * already travels on the documents overview (issue #292) — this service only adds what no existing
 * endpoint answers, in a bounded number of queries (#313 spirit: batch, never per-review).
 *
 * <p>Names honour per-review anonymity (issue #413) by resolving through each document's {@link
 * ReviewIdentityResolver.ReviewIdentities}. Activity on PRIVATE-participation reviews follows the
 * thread-visibility rule conservatively: annotation events of such a review are shown only to its
 * owner (payloads stay unparsed, so per-thread filtering is out of reach — hiding is the safe
 * default; the caller's own events are excluded anyway).
 */
@Service
public class DashboardService {

  /** The dashboard is a glance, not an archive. */
  static final int MAX_REPLIES = 15;

  static final int MAX_ACTIVITY = 20;
  static final int REPLY_EXCERPT_CHARS = 240;
  static final int CONTEXT_EXCERPT_CHARS = 120;

  /** Documents considered for the aggregates — far above any real workspace. */
  static final int MAX_DOCUMENTS = 200;

  static final List<String> FEED_EVENT_TYPES =
      List.of(
          "annotation.created",
          "annotation.resolved",
          "annotation.reopened",
          "workflow.transition",
          "document.due_date.changed");

  private final DocumentRepository documents;
  private final AnnotationRepository annotations;
  private final CommentRepository comments;
  private final AuditEventRepository auditEvents;
  private final ReviewIdentityResolver identity;

  public DashboardService(
      DocumentRepository documents,
      AnnotationRepository annotations,
      CommentRepository comments,
      AuditEventRepository auditEvents,
      ReviewIdentityResolver identity) {
    this.documents = documents;
    this.annotations = annotations;
    this.comments = comments;
    this.auditEvents = auditEvents;
    this.identity = identity;
  }

  /**
   * One reply by someone else in a thread the caller started or joined. {@code authorId} is the
   * REAL user id when the review does not anonymise the author towards the caller, and null for a
   * pseudonymised identity (issue #413) — the client links and loads avatars only when present.
   */
  public record ReplyView(
      UUID commentId,
      UUID annotationId,
      UUID documentId,
      String documentTitle,
      String documentSlug,
      UUID authorId,
      String authorDisplayName,
      String body,
      String annotationExcerpt,
      Instant createdAt) {}

  /** One feed entry from the audit trail; {@code actorId} follows the same anonymity rule. */
  public record ActivityView(
      String type,
      UUID documentId,
      String documentTitle,
      String documentSlug,
      UUID actorId,
      String actorDisplayName,
      Instant createdAt) {}

  public record DashboardView(
      List<ReplyView> replies, List<ActivityView> activity, int resolvedThisWeek) {}

  @Transactional(readOnly = true)
  public DashboardView overview(UUID actor) {
    List<Document> visible =
        documents
            .findVisibleTo(actor, null, PageRequest.of(0, MAX_DOCUMENTS, Sort.by("updatedAt")))
            .getContent();
    if (visible.isEmpty()) {
      return new DashboardView(List.of(), List.of(), 0);
    }
    Map<UUID, Document> documentById =
        visible.stream().collect(toMap(Document::getId, document -> document));
    List<UUID> documentIds = List.copyOf(documentById.keySet());
    // Identities are resolved lazily per document and cached for this pass —
    // replies and activity together touch a handful of reviews, not N.
    Map<UUID, ReviewIdentityResolver.ReviewIdentities> identities = new HashMap<>();
    Function<UUID, ReviewIdentityResolver.ReviewIdentities> identitiesOf =
        documentId -> identities.computeIfAbsent(documentId, id -> identity.forDocument(id, actor));

    List<ReplyView> replies = replies(documentIds, documentById, actor, identitiesOf);
    List<ActivityView> activity = activity(documentIds, documentById, actor, identitiesOf);
    int resolvedThisWeek =
        (int)
            auditEvents.countByDocumentIdInAndEventTypeAndCreatedAtAfter(
                documentIds, "annotation.resolved", Instant.now().minus(Duration.ofDays(7)));
    return new DashboardView(replies, activity, resolvedThisWeek);
  }

  private List<ReplyView> replies(
      List<UUID> documentIds,
      Map<UUID, Document> documentById,
      UUID actor,
      Function<UUID, ReviewIdentityResolver.ReviewIdentities> identitiesOf) {
    List<Comment> latest =
        comments.repliesToViewer(documentIds, actor, PageRequest.of(0, MAX_REPLIES));
    if (latest.isEmpty()) {
      return List.of();
    }
    List<UUID> annotationIds = latest.stream().map(Comment::getAnnotationId).distinct().toList();
    Map<UUID, Annotation> annotationById =
        annotations.findAllById(annotationIds).stream()
            .collect(toMap(Annotation::getId, annotation -> annotation));
    // The thread's opening line as context — batched like the views do (#393).
    Map<UUID, String> contextByAnnotation =
        comments.findFirstByAnnotationIdIn(annotationIds).stream()
            .collect(
                toMap(
                    AnnotationFirstComment::getAnnotationId,
                    first -> excerpt(first.getBody(), CONTEXT_EXCERPT_CHARS)));
    return latest.stream()
        .map(
            comment -> {
              Annotation annotation = annotationById.get(comment.getAnnotationId());
              Document document = documentById.get(annotation.getDocumentId());
              ReviewIdentityResolver.ReviewIdentities documentIdentities =
                  identitiesOf.apply(document.getId());
              return new ReplyView(
                  comment.getId(),
                  comment.getAnnotationId(),
                  document.getId(),
                  document.getTitle(),
                  document.getSlug(),
                  realIdOrNull(documentIdentities, comment.getAuthorId()),
                  documentIdentities.displayName(comment.getAuthorId()),
                  excerpt(comment.getBody(), REPLY_EXCERPT_CHARS),
                  contextByAnnotation.get(comment.getAnnotationId()),
                  comment.getCreatedAt());
            })
        .toList();
  }

  private List<ActivityView> activity(
      List<UUID> documentIds,
      Map<UUID, Document> documentById,
      UUID actor,
      Function<UUID, ReviewIdentityResolver.ReviewIdentities> identitiesOf) {
    // Fetch beyond the cap: own events and PRIVATE-hidden ones are dropped below.
    List<AuditEvent> events =
        auditEvents.findByDocumentIdInAndEventTypeInOrderByCreatedAtDesc(
            documentIds, FEED_EVENT_TYPES, PageRequest.of(0, MAX_ACTIVITY * 3));
    return events.stream()
        .filter(event -> !actor.equals(event.getActorId()))
        .filter(event -> visibleActivity(event, documentById.get(event.getDocumentId()), actor))
        .limit(MAX_ACTIVITY)
        .map(
            event -> {
              Document document = documentById.get(event.getDocumentId());
              ReviewIdentityResolver.ReviewIdentities documentIdentities =
                  identitiesOf.apply(document.getId());
              return new ActivityView(
                  event.getEventType(),
                  document.getId(),
                  document.getTitle(),
                  document.getSlug(),
                  event.getActorId() == null
                      ? null
                      : realIdOrNull(documentIdentities, event.getActorId()),
                  event.getActorId() == null
                      ? null
                      : documentIdentities.displayName(event.getActorId()),
                  event.getCreatedAt());
            })
        .toList();
  }

  /**
   * The PRIVATE-participation rule (#413), applied conservatively: annotation events of a PRIVATE
   * review are shown only to its owner — the audit payload is not parsed, so per-thread visibility
   * cannot be decided here and hiding is the safe default.
   */
  private static boolean visibleActivity(AuditEvent event, Document document, UUID actor) {
    if (!event.getEventType().startsWith("annotation.")) {
      return true;
    }
    if (document.getThreadParticipation() != ThreadParticipation.PRIVATE) {
      return true;
    }
    return document.getOwnerId().equals(actor);
  }

  /**
   * The id the caller may see: the real user id when the identities expose it unchanged, null when
   * the review pseudonymises this author towards the caller (issue #413) — a pseudonym token must
   * neither link to a profile nor load an avatar.
   */
  private static UUID realIdOrNull(
      ReviewIdentityResolver.ReviewIdentities identities, UUID userId) {
    return userId.equals(identities.exposedAuthorId(userId)) ? userId : null;
  }

  private static String excerpt(String body, int max) {
    return body.length() <= max ? body : body.substring(0, max);
  }
}
