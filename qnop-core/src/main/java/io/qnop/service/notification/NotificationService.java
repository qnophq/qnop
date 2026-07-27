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
package io.qnop.service.notification;

import io.qnop.entity.Document;
import io.qnop.entity.Notification;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.NotificationRepository;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.review.ReviewIdentityResolver;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reading half of the in-app inbox (issue #538, ADR-0051): the caller's own notifications,
 * rendered for the caller.
 *
 * <p>"For the caller" is the whole point. A stored row is deliberately identity-free — ids only —
 * because under per-review anonymity (ADR-0038) a name belongs to a (review, viewer) pair and a
 * review's privacy can change after the fact. So every read resolves the actor's name through
 * {@link ReviewIdentityResolver}, and re-checks that the caller may still see the review at all: a
 * notification about a review they were since removed from renders as a tombstone rather than
 * leaking its title.
 *
 * <p>Both of those cost queries per <em>document</em>, not per notification, so a page resolves
 * each distinct review once and reuses it across that page's rows.
 */
@Service
public class NotificationService {

  private final NotificationRepository notifications;
  private final DocumentRepository documents;
  private final DocumentAccessService access;
  private final ReviewIdentityResolver identity;

  public NotificationService(
      NotificationRepository notifications,
      DocumentRepository documents,
      DocumentAccessService access,
      ReviewIdentityResolver identity) {
    this.notifications = notifications;
    this.documents = documents;
    this.access = access;
    this.identity = identity;
  }

  /** One page of the recipient's inbox, newest first; {@code unread} null means "both". */
  @Transactional(readOnly = true)
  public NotificationPageView list(UUID recipientId, Boolean unread, int page, int size) {
    PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<Notification> found =
        unread == null
            ? notifications.findByRecipientId(recipientId, pageable)
            : notifications.findByRecipientIdAndRead(recipientId, unread, pageable);
    RenderContext context = new RenderContext(recipientId);
    List<NotificationView> items =
        found.getContent().stream().map(notification -> render(notification, context)).toList();
    return new NotificationPageView(
        items,
        found.getTotalElements(),
        page,
        size,
        notifications.countByRecipientIdAndReadAtIsNull(recipientId));
  }

  /** The badge's number. */
  @Transactional(readOnly = true)
  public long unreadCount(UUID recipientId) {
    return notifications.countByRecipientIdAndReadAtIsNull(recipientId);
  }

  /**
   * One notification of the caller. Empty when the id is unknown <em>or</em> belongs to somebody
   * else — the two are indistinguishable on purpose, so the endpoint cannot be used to probe which
   * ids exist.
   */
  @Transactional(readOnly = true)
  public Optional<NotificationView> get(UUID recipientId, UUID notificationId) {
    return notifications
        .findByIdAndRecipientId(notificationId, recipientId)
        .map(notification -> render(notification, new RenderContext(recipientId)));
  }

  /** Marks one notification read; idempotent, and false when it is not the caller's. */
  @Transactional
  public boolean markRead(UUID recipientId, UUID notificationId) {
    return notifications
        .findByIdAndRecipientId(notificationId, recipientId)
        .map(
            notification -> {
              notification.markRead(Instant.now());
              notifications.save(notification);
              return true;
            })
        .orElse(false);
  }

  /** Marks the caller's whole inbox read and reports how many rows that touched. */
  @Transactional
  public int markAllRead(UUID recipientId) {
    return notifications.markAllRead(recipientId, Instant.now());
  }

  // --- rendering -----------------------------------------------------------

  private NotificationView render(Notification notification, RenderContext context) {
    UUID documentId = notification.getDocumentId();
    boolean accessible = documentId != null && context.isVisible(documentId, access);
    Document document = accessible ? context.document(documentId, documents) : null;
    if (document == null) {
      return tombstone(notification);
    }
    String actorName = context.actorName(document.getId(), notification.getActorId(), identity);
    String title = title(notification, actorName);
    return new NotificationView(
        notification.getId(),
        notification.getType().name(),
        title,
        body(notification, actorName, document.getTitle()),
        notification.getExcerpt(),
        actorName,
        document.getId(),
        document.getTitle(),
        actionPath(notification, document),
        actionLabel(notification),
        true,
        notification.getReadAt(),
        notification.getCreatedAt());
  }

  /**
   * What a notification looks like once the caller may no longer see its review — the record that
   * something happened survives, everything that would describe it does not.
   */
  private NotificationView tombstone(Notification notification) {
    return new NotificationView(
        notification.getId(),
        notification.getType().name(),
        "A review you no longer have access to",
        "This notification refers to a review that is no longer available to you.",
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        notification.getReadAt(),
        notification.getCreatedAt());
  }

  private String title(Notification notification, String actorName) {
    return switch (notification.getType()) {
      case MENTION -> actorName + " mentioned you";
      case PARTICIPANT_ADDED -> "You were added to a review";
      case ANNOTATION_CREATED -> actorName + " raised an annotation";
      case ANNOTATION_DECIDED -> "An annotation was " + decision(notification);
      case COMMENT_ADDED -> actorName + " replied";
      case VERSION_UPLOADED -> "New version uploaded";
      case WORKFLOW_CHANGED -> "The review moved to " + humanState(notification.getToState());
    };
  }

  private String body(Notification notification, String actorName, String documentTitle) {
    String review = "“" + documentTitle + "”";
    return switch (notification.getType()) {
      case MENTION -> actorName + " mentioned you in a comment on " + review + ".";
      case PARTICIPANT_ADDED -> actorName + " added you as a reviewer on " + review + ".";
      case ANNOTATION_CREATED -> actorName + " raised a new annotation on " + review + ".";
      case ANNOTATION_DECIDED ->
          actorName + " " + decision(notification) + " an annotation on " + review + ".";
      case COMMENT_ADDED -> actorName + " replied to a discussion on " + review + ".";
      case VERSION_UPLOADED ->
          actorName
              + " uploaded version "
              + notification.getVersionNumber()
              + " of "
              + review
              + ".";
      case WORKFLOW_CHANGED ->
          review
              + " moved from "
              + humanState(notification.getFromState())
              + " to "
              + humanState(notification.getToState())
              + ".";
    };
  }

  /** The relative SPA route — built from ids on read, so a renamed review keeps working links. */
  private String actionPath(Notification notification, Document document) {
    String segment = document.getSlug() != null ? document.getSlug() : document.getId().toString();
    String base = "/reviews/" + segment;
    if (notification.getAnnotationId() != null) {
      String path = base + "?annotation=" + notification.getAnnotationId();
      return notification.getCommentId() == null
          ? path
          : path + "&comment=" + notification.getCommentId();
    }
    if (notification.getVersionNumber() != null) {
      return base + "?version=" + notification.getVersionNumber();
    }
    return base;
  }

  private String actionLabel(Notification notification) {
    return notification.getAnnotationId() != null ? "Open annotation" : "Open review";
  }

  private String decision(Notification notification) {
    String decision = notification.getDecision();
    return decision == null || decision.isBlank() ? "decided" : decision;
  }

  /** {@code CHANGES_REQUESTED} → {@code Changes requested}. */
  private static String humanState(String raw) {
    if (raw == null || raw.isBlank()) {
      return "another state";
    }
    String lower = raw.replace('_', ' ').toLowerCase();
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  /**
   * Per-request memo for the two per-document lookups a page would otherwise repeat per row: the
   * visibility check and the identity resolution (roughly six queries each).
   */
  private static final class RenderContext {
    private final UUID recipientId;
    private final Map<UUID, Boolean> visibility = new HashMap<>();
    private final Map<UUID, Optional<Document>> documentsById = new HashMap<>();
    private final Map<UUID, ReviewIdentityResolver.ReviewIdentities> identities = new HashMap<>();

    private RenderContext(UUID recipientId) {
      this.recipientId = recipientId;
    }

    boolean isVisible(UUID documentId, DocumentAccessService access) {
      return visibility.computeIfAbsent(documentId, id -> access.isVisible(id, recipientId, false));
    }

    Document document(UUID documentId, DocumentRepository documents) {
      return documentsById.computeIfAbsent(documentId, documents::findById).orElse(null);
    }

    String actorName(UUID documentId, UUID actorId, ReviewIdentityResolver identity) {
      if (actorId == null) {
        return "System";
      }
      String name =
          identities
              .computeIfAbsent(documentId, id -> identity.forDocument(id, recipientId))
              .displayName(actorId);
      return name == null || name.isBlank() ? "A participant" : name;
    }
  }

  /**
   * One rendered notification, ready for the API layer to map. {@code type} travels as its name
   * rather than as the entity enum: entities do not cross into the web layer (ArchUnit), and the
   * wire model has its own generated enum anyway.
   */
  public record NotificationView(
      UUID id,
      String type,
      String title,
      String body,
      String preview,
      String actorName,
      UUID documentId,
      String documentTitle,
      String actionPath,
      String actionLabel,
      boolean accessible,
      Instant readAt,
      Instant createdAt) {}

  /** One page of the caller's inbox plus the inbox-wide unread total. */
  public record NotificationPageView(
      List<NotificationView> items, long total, int page, int size, long unreadTotal) {}
}
