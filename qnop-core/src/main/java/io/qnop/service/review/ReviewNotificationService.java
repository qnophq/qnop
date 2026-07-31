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
import io.qnop.entity.Comment;
import io.qnop.entity.CommentMention;
import io.qnop.entity.Document;
import io.qnop.entity.NotificationType;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.User;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentMentionRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamMemberProjection;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.mail.MailTemplateKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides who should hear about a committed {@link ReviewEvent} and what they should be told (issue
 * #316), then hands the result to every delivery channel (issue #538, ADR-0051).
 *
 * <p>This class owns the <em>policy</em> — grown issue by issue and not derivable from the event
 * alone: replies follow the thread, a dismissal must reach the author who can reopen it (#408), a
 * mention outranks the reply containing it so nobody hears the same thing twice (#462), derived
 * workflow flips stay silent because the annotation that caused them already spoke. It resolves
 * that policy once; {@link ReviewNotificationSink}s deliver it. What it deliberately does
 * <em>not</em> decide is anything channel-specific — an e-mail address, the mail switch, the mail
 * opt-outs — so that muting mail cannot mute the in-app inbox.
 *
 * <p>Anonymous reviews stay anonymous: the actor's name in the mail variables is resolved per
 * recipient through {@link ReviewIdentityResolver}, and the persisted row keeps ids only so the
 * inbox re-resolves at read time.
 *
 * <p>Runs on the notification executor after the triggering transaction committed — a failure here
 * is logged and never disturbs the review itself.
 */
@Service
public class ReviewNotificationService {

  private static final Logger log = LoggerFactory.getLogger(ReviewNotificationService.class);

  /** Bodies quote at most this many characters of an annotation/comment. */
  private static final int EXCERPT_MAX = 140;

  private final DocumentRepository documents;
  private final AnnotationRepository annotations;
  private final CommentRepository comments;
  private final CommentMentionRepository commentMentions;
  private final ReviewParticipantRepository participants;
  private final TeamMembershipRepository teamMembers;
  private final UserRepository users;
  private final ApplicationSettingsService settings;
  private final ReviewIdentityResolver identity;
  private final List<ReviewNotificationSink> sinks;

  public ReviewNotificationService(
      DocumentRepository documents,
      AnnotationRepository annotations,
      CommentRepository comments,
      CommentMentionRepository commentMentions,
      ReviewParticipantRepository participants,
      TeamMembershipRepository teamMembers,
      UserRepository users,
      ApplicationSettingsService settings,
      ReviewIdentityResolver identity,
      List<ReviewNotificationSink> sinks) {
    this.documents = documents;
    this.annotations = annotations;
    this.comments = comments;
    this.commentMentions = commentMentions;
    this.participants = participants;
    this.teamMembers = teamMembers;
    this.users = users;
    this.settings = settings;
    this.identity = identity;
    this.sinks = sinks;
  }

  /**
   * Resolves a committed event and offers the result to every channel; quietly done when nothing
   * applies.
   *
   * <p>Each recipient's candidates are ranked (see {@link NotificationType}) and each sink gets the
   * first one it accepts — so a recipient hears about an event once per channel, at the most
   * specific level that channel is willing to deliver.
   */
  @Transactional(readOnly = true)
  public void dispatch(ReviewEvent event) {
    Map<UUID, List<ReviewNotificationIntent>> byRecipient = resolve(event);
    for (ReviewNotificationSink sink : sinks) {
      for (List<ReviewNotificationIntent> candidates : byRecipient.values()) {
        candidates.stream()
            .filter(sink::accepts)
            .findFirst()
            .ifPresent(intent -> deliverQuietly(sink, intent));
      }
    }
  }

  /** One channel failing is that channel's problem — the others still deliver. */
  private void deliverQuietly(ReviewNotificationSink sink, ReviewNotificationIntent intent) {
    try {
      sink.deliver(intent);
    } catch (RuntimeException ex) {
      log.warn(
          "{} could not deliver a {} notification to {}",
          sink.getClass().getSimpleName(),
          intent.type(),
          intent.recipient().getId(),
          ex);
    }
  }

  /** The event's intents, grouped per recipient and ranked most-specific first. */
  private Map<UUID, List<ReviewNotificationIntent>> resolve(ReviewEvent event) {
    if (event instanceof ReviewEvent.ReviewDeleted deleted) {
      // Before the lookup, deliberately: the review is gone, which is the whole
      // message (issue #421).
      return byRecipient(reviewDeleted(deleted));
    }
    Optional<Document> loaded = documents.findById(event.documentId());
    if (loaded.isEmpty()) {
      return Map.of(); // deleted between commit and dispatch — nothing to say
    }
    Document document = loaded.get();
    List<ReviewNotificationIntent> intents =
        switch (event) {
          case ReviewEvent.ParticipantAdded added -> participantAdded(document, added);
          case ReviewEvent.AnnotationCreated created -> annotationCreated(document, created);
          case ReviewEvent.AnnotationDecided decided ->
              annotationDecided(
                  document,
                  decided.annotationId(),
                  decided.actorId(),
                  decided.reopened() ? "reopened" : "resolved");
          // The dismissed author is the one recipient who MUST hear of it (issue #408) —
          // their reopen right is worthless unless they learn the concern was closed.
          case ReviewEvent.AnnotationDismissed dismissed ->
              annotationDecided(
                  document, dismissed.annotationId(), dismissed.actorId(), "dismissed");
          case ReviewEvent.CommentAdded comment -> commentAdded(document, comment);
          case ReviewEvent.VersionUploaded uploaded -> versionUploaded(document, uploaded);
          case ReviewEvent.WorkflowChanged changed -> workflowChanged(document, changed);
          // Handled above, before the document lookup that this branch depends on.
          case ReviewEvent.ReviewDeleted ignored -> List.of();
        };
    return byRecipient(intents);
  }

  /**
   * Tells the owner their review was destroyed.
   *
   * <p>Only the owner: everyone else's stake in a review is the discussion, and there is no
   * discussion left to point them at. The owner is the one person who has to know the thing they
   * started is gone.
   *
   * <p>The intent carries no {@code documentId} on purpose — {@code notification.document_id}
   * cascades with the document, so a row naming the deleted review would be deleted in the same
   * statement. The title travels as the excerpt instead.
   */
  private List<ReviewNotificationIntent> reviewDeleted(ReviewEvent.ReviewDeleted event) {
    if (event.ownerId() == null || event.ownerId().equals(event.actorId())) {
      // An admin deleting their own review already knows.
      return List.of();
    }
    return users
        .findById(event.ownerId())
        .<List<ReviewNotificationIntent>>map(
            owner ->
                List.of(
                    ReviewNotificationIntent.to(
                            owner, NotificationType.REVIEW_DELETED, MailTemplateKey.REVIEW_DELETED)
                        .actor(event.actorId())
                        .excerpt(event.title())
                        .var("documentTitle", event.title())
                        .build()))
        .orElseGet(List::of);
  }

  private Map<UUID, List<ReviewNotificationIntent>> byRecipient(
      List<ReviewNotificationIntent> intents) {
    return intents.stream()
        .collect(
            Collectors.groupingBy(
                intent -> intent.recipient().getId(),
                LinkedHashMap::new,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    candidates -> {
                      List<ReviewNotificationIntent> ranked = new ArrayList<>(candidates);
                      ranked.sort(Comparator.comparingInt(intent -> intent.type().ordinal()));
                      return ranked;
                    })));
  }

  private List<ReviewNotificationIntent> participantAdded(
      Document document, ReviewEvent.ParticipantAdded event) {
    Set<UUID> candidates = new LinkedHashSet<>();
    if (event.userId() != null) {
      candidates.add(event.userId());
    } else if (event.teamId() != null) {
      teamMembers.findMembersByTeamId(event.teamId()).stream()
          .map(TeamMemberProjection::userId)
          .forEach(candidates::add);
    }
    // The owner is not "added as a reviewer" of their own review, even via a team.
    candidates.remove(document.getOwnerId());
    // Adding is owner/admin-only and both act under their public name — no anonymity concern.
    String actorName =
        users.findById(event.actorId()).map(User::getDisplayName).orElse("An administrator");
    List<ReviewNotificationIntent> intents = new ArrayList<>();
    for (User recipient : recipients(candidates, event.actorId())) {
      intents.add(
          ReviewNotificationIntent.to(
                  recipient,
                  NotificationType.PARTICIPANT_ADDED,
                  MailTemplateKey.REVIEW_PARTICIPANT_ADDED)
              .vars(baseVars(document, recipient))
              .var("actorName", actorName)
              .var("actionUrl", reviewUrl(document))
              .document(document.getId())
              .actor(event.actorId())
              .build());
    }
    return intents;
  }

  private List<ReviewNotificationIntent> annotationCreated(
      Document document, ReviewEvent.AnnotationCreated event) {
    if (annotations.findById(event.annotationId()).isEmpty()) {
      return List.of();
    }
    Comment opening =
        comments.findByAnnotationIdOrderByCreatedAtAsc(event.annotationId()).stream()
            .findFirst()
            .orElse(null);
    String excerpt = opening == null ? "" : excerpt(opening.getBody());
    List<ReviewNotificationIntent> intents = new ArrayList<>();
    // Mentions in the opening comment outrank the annotation notice — a mentioned owner
    // is told they were named, not merely that an annotation appeared (issue #462).
    if (opening != null) {
      intents.addAll(
          mentionIntents(
              document,
              opening.getId(),
              event.annotationId(),
              event.actorId(),
              excerpt,
              annotationUrl(document, event.annotationId()) + "&comment=" + opening.getId()));
    }
    for (User recipient : recipients(Set.of(document.getOwnerId()), event.actorId())) {
      intents.add(
          ReviewNotificationIntent.to(
                  recipient,
                  NotificationType.ANNOTATION_CREATED,
                  MailTemplateKey.REVIEW_ANNOTATION_CREATED)
              .vars(baseVars(document, recipient))
              .var("actorName", actorNameFor(document, recipient, event.actorId()))
              .var("annotationExcerpt", excerpt)
              .var("actionUrl", annotationUrl(document, event.annotationId()))
              .document(document.getId())
              .actor(event.actorId())
              .annotation(event.annotationId())
              .excerpt(excerpt)
              .build());
    }
    return intents;
  }

  private List<ReviewNotificationIntent> annotationDecided(
      Document document, UUID annotationId, UUID actorId, String decision) {
    Optional<Annotation> annotation = annotations.findById(annotationId);
    if (annotation.isEmpty()) {
      return List.of();
    }
    Set<UUID> candidates =
        new LinkedHashSet<>(List.of(document.getOwnerId(), annotation.get().getAuthorId()));
    String excerpt = firstCommentExcerpt(annotationId);
    List<ReviewNotificationIntent> intents = new ArrayList<>();
    for (User recipient : recipients(candidates, actorId)) {
      intents.add(
          ReviewNotificationIntent.to(
                  recipient,
                  NotificationType.ANNOTATION_DECIDED,
                  MailTemplateKey.REVIEW_ANNOTATION_DECIDED)
              .vars(baseVars(document, recipient))
              .var("actorName", actorNameFor(document, recipient, actorId))
              .var("annotationExcerpt", excerpt)
              .var("decision", decision)
              .var("actionUrl", annotationUrl(document, annotationId))
              .document(document.getId())
              .actor(actorId)
              .annotation(annotationId)
              .excerpt(excerpt)
              .decision(decision)
              .build());
    }
    return intents;
  }

  private List<ReviewNotificationIntent> commentAdded(
      Document document, ReviewEvent.CommentAdded event) {
    Optional<Annotation> annotation = annotations.findById(event.annotationId());
    if (annotation.isEmpty()) {
      return List.of();
    }
    // Slack-thread semantics: whoever started or joined the discussion follows it.
    Set<UUID> candidates = new LinkedHashSet<>();
    candidates.add(annotation.get().getAuthorId());
    List<Comment> thread = comments.findByAnnotationIdOrderByCreatedAtAsc(event.annotationId());
    thread.forEach(comment -> candidates.add(comment.getAuthorId()));
    String excerpt =
        thread.stream()
            .filter(comment -> comment.getId().equals(event.commentId()))
            .findFirst()
            .map(comment -> excerpt(comment.getBody()))
            .orElse("");
    String actionUrl =
        annotationUrl(document, event.annotationId()) + "&comment=" + event.commentId();
    List<ReviewNotificationIntent> intents =
        new ArrayList<>(
            mentionIntents(
                document,
                event.commentId(),
                event.annotationId(),
                event.actorId(),
                excerpt,
                actionUrl));
    for (User recipient : recipients(candidates, event.actorId())) {
      intents.add(
          ReviewNotificationIntent.to(
                  recipient, NotificationType.COMMENT_ADDED, MailTemplateKey.REVIEW_COMMENT_ADDED)
              .vars(baseVars(document, recipient))
              .var("actorName", actorNameFor(document, recipient, event.actorId()))
              .var("commentExcerpt", excerpt)
              .var("actionUrl", actionUrl)
              .document(document.getId())
              .actor(event.actorId())
              .annotation(event.annotationId())
              .comment(event.commentId())
              .excerpt(excerpt)
              .build());
    }
    return intents;
  }

  private List<ReviewNotificationIntent> versionUploaded(
      Document document, ReviewEvent.VersionUploaded event) {
    if (event.versionNumber() <= 1) {
      // The first version IS the review's creation — participants joining later get
      // the invitation instead; there is no "new" version to announce.
      return List.of();
    }
    // Uploads are owner-only and the owner acts under their public name (issue #413).
    String actorName =
        users.findById(event.actorId()).map(User::getDisplayName).orElse("The owner");
    List<ReviewNotificationIntent> intents = new ArrayList<>();
    for (User recipient : recipients(reviewCircle(document), event.actorId())) {
      intents.add(
          ReviewNotificationIntent.to(
                  recipient,
                  NotificationType.VERSION_UPLOADED,
                  MailTemplateKey.REVIEW_VERSION_UPLOADED)
              .vars(baseVars(document, recipient))
              .var("actorName", actorName)
              .var("versionNumber", String.valueOf(event.versionNumber()))
              .var("actionUrl", reviewUrl(document) + "?version=" + event.versionNumber())
              .document(document.getId())
              .actor(event.actorId())
              .versionNumber(event.versionNumber())
              .build());
    }
    return intents;
  }

  private List<ReviewNotificationIntent> workflowChanged(
      Document document, ReviewEvent.WorkflowChanged event) {
    if (!event.manual()) {
      // Derived IN_REVIEW ⇄ CHANGES_REQUESTED flips are announced by the annotation
      // notifications that caused them — a second one would say the same thing twice.
      return List.of();
    }
    List<ReviewNotificationIntent> intents = new ArrayList<>();
    for (User recipient : recipients(reviewCircle(document), event.actorId())) {
      intents.add(
          ReviewNotificationIntent.to(
                  recipient,
                  NotificationType.WORKFLOW_CHANGED,
                  MailTemplateKey.REVIEW_WORKFLOW_CHANGED)
              .vars(baseVars(document, recipient))
              .var("oldState", humanState(event.fromState()))
              .var("newState", humanState(event.toState()))
              .var("actionUrl", reviewUrl(document))
              .document(document.getId())
              .actor(event.actorId())
              .transition(event.fromState(), event.toState())
              .build());
    }
    return intents;
  }

  /**
   * The mention intents for one comment. Mentions are resolved at write time and never persisted
   * for anonymous reviews, so nothing here can leak an identity.
   */
  private List<ReviewNotificationIntent> mentionIntents(
      Document document,
      UUID commentId,
      UUID annotationId,
      UUID actorId,
      String excerpt,
      String actionUrl) {
    Set<UUID> mentioned =
        commentMentions.findByCommentId(commentId).stream()
            .map(CommentMention::getMentionedUserId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (mentioned.isEmpty()) {
      return List.of();
    }
    List<ReviewNotificationIntent> intents = new ArrayList<>();
    for (User recipient : recipients(mentioned, actorId)) {
      intents.add(
          ReviewNotificationIntent.to(
                  recipient, NotificationType.MENTION, MailTemplateKey.REVIEW_MENTION)
              .vars(baseVars(document, recipient))
              .var("actorName", actorNameFor(document, recipient, actorId))
              .var("commentExcerpt", excerpt)
              .var("actionUrl", actionUrl)
              .document(document.getId())
              .actor(actorId)
              .annotation(annotationId)
              .comment(commentId)
              .excerpt(excerpt)
              .build());
    }
    return intents;
  }

  /** Everyone attached to the review: the owner, direct participants, and team members. */
  private Set<UUID> reviewCircle(Document document) {
    Set<UUID> circle = new LinkedHashSet<>();
    circle.add(document.getOwnerId());
    for (ReviewParticipant participant : participants.findByDocumentId(document.getId())) {
      if (participant.getUserId() != null) {
        circle.add(participant.getUserId());
      } else if (participant.getTeamId() != null) {
        teamMembers.findMembersByTeamId(participant.getTeamId()).stream()
            .map(TeamMemberProjection::userId)
            .forEach(circle::add);
      }
    }
    return circle;
  }

  /**
   * The users an event may be told to at all: the candidates minus the actor, restricted to enabled
   * accounts. Deliberately nothing else — an address and the mail opt-outs are the mail sink's
   * business (ADR-0051), so a user who muted mail still gets the in-app record.
   */
  private List<User> recipients(Set<UUID> candidates, UUID actorId) {
    Set<UUID> ids = new LinkedHashSet<>(candidates);
    ids.remove(actorId);
    if (ids.isEmpty()) {
      return List.of();
    }
    return users.findAllById(ids).stream().filter(User::isEnabled).toList();
  }

  /**
   * The actor's name as THIS recipient is allowed to see it (issue #413): real in normal reviews,
   * pseudonymous in anonymous ones unless the actor is the owner or the recipient themselves.
   */
  private String actorNameFor(Document document, User recipient, UUID actorId) {
    String name = identity.forDocument(document.getId(), recipient.getId()).displayName(actorId);
    return name == null || name.isBlank() ? "A participant" : name;
  }

  private Map<String, Object> baseVars(Document document, User recipient) {
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("siteName", settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME));
    vars.put("recipientName", recipient.getDisplayName());
    vars.put("documentTitle", document.getTitle());
    return vars;
  }

  private String reviewUrl(Document document) {
    String base = settings.getString(ApplicationSettingKey.GENERAL_BASE_URL);
    if (base == null || base.isBlank()) {
      // The mail still goes out, but its links are relative and thus dead in a
      // mail client — configure Settings -> General -> Base URL.
      log.warn(
          "general.base_url is not configured — notification mail links will be relative/broken");
      base = "";
    }
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    String segment = document.getSlug() != null ? document.getSlug() : document.getId().toString();
    return base + "/reviews/" + segment;
  }

  private String annotationUrl(Document document, UUID annotationId) {
    return reviewUrl(document) + "?annotation=" + annotationId;
  }

  private String firstCommentExcerpt(UUID annotationId) {
    return comments.findByAnnotationIdOrderByCreatedAtAsc(annotationId).stream()
        .findFirst()
        .map(comment -> excerpt(comment.getBody()))
        .orElse("");
  }

  /** One quotable line: markdown noise stripped, whitespace collapsed, capped with an ellipsis. */
  static String excerpt(String body) {
    if (body == null) {
      return "";
    }
    String flat =
        body.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ") // images
            .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1") // links → their label
            .replaceAll("[`*_>#~]", "")
            .replaceAll("\\s+", " ")
            .trim();
    return flat.length() <= EXCERPT_MAX ? flat : flat.substring(0, EXCERPT_MAX - 1).trim() + "…";
  }

  /** {@code CHANGES_REQUESTED} → {@code Changes requested} — states as humans read them. */
  static String humanState(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String lower = raw.replace('_', ' ').toLowerCase();
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }
}
