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
import io.qnop.entity.Document;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.User;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamMemberProjection;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserSettingRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserSettingKey;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a committed {@link ReviewEvent} into best-effort emails (issue #316): resolves who cares
 * (owner, participants, thread members — never the actor, never disabled users, never anyone who
 * opted out), keeps anonymous reviews anonymous by resolving the actor's name per recipient through
 * {@link ReviewIdentityResolver}, and hands the rendered template to {@link MailService}.
 *
 * <p>Runs on the notification executor after the triggering transaction committed — a failure here
 * is logged by the listener and never disturbs the review itself.
 */
@Service
public class ReviewNotificationService {

  /** Mail bodies quote at most this many characters of an annotation/comment. */
  private static final int EXCERPT_MAX = 140;

  private final DocumentRepository documents;
  private final AnnotationRepository annotations;
  private final CommentRepository comments;
  private final ReviewParticipantRepository participants;
  private final TeamMembershipRepository teamMembers;
  private final UserRepository users;
  private final UserSettingRepository userSettings;
  private final ApplicationSettingsService settings;
  private final ReviewIdentityResolver identity;
  private final MailService mail;

  public ReviewNotificationService(
      DocumentRepository documents,
      AnnotationRepository annotations,
      CommentRepository comments,
      ReviewParticipantRepository participants,
      TeamMembershipRepository teamMembers,
      UserRepository users,
      UserSettingRepository userSettings,
      ApplicationSettingsService settings,
      ReviewIdentityResolver identity,
      MailService mail) {
    this.documents = documents;
    this.annotations = annotations;
    this.comments = comments;
    this.participants = participants;
    this.teamMembers = teamMembers;
    this.users = users;
    this.userSettings = userSettings;
    this.settings = settings;
    this.identity = identity;
    this.mail = mail;
  }

  /** Sends the mails a committed review event calls for; quietly done when nothing applies. */
  @Transactional(readOnly = true)
  public void dispatch(ReviewEvent event) {
    if (!settings.getBoolean(ApplicationSettingKey.NOTIFICATIONS_REVIEW_EMAILS_ENABLED)) {
      return;
    }
    Optional<Document> loaded = documents.findById(event.documentId());
    if (loaded.isEmpty()) {
      return; // deleted between commit and dispatch — nothing to say
    }
    Document document = loaded.get();
    switch (event) {
      case ReviewEvent.ParticipantAdded added -> participantAdded(document, added);
      case ReviewEvent.AnnotationCreated created -> annotationCreated(document, created);
      case ReviewEvent.AnnotationDecided decided -> annotationDecided(document, decided);
      case ReviewEvent.CommentAdded comment -> commentAdded(document, comment);
      case ReviewEvent.VersionUploaded uploaded -> versionUploaded(document, uploaded);
      case ReviewEvent.WorkflowChanged changed -> workflowChanged(document, changed);
    }
  }

  private void participantAdded(Document document, ReviewEvent.ParticipantAdded event) {
    Set<UUID> recipients = new LinkedHashSet<>();
    if (event.userId() != null) {
      recipients.add(event.userId());
    } else if (event.teamId() != null) {
      teamMembers.findMembersByTeamId(event.teamId()).stream()
          .map(TeamMemberProjection::userId)
          .forEach(recipients::add);
    }
    // The owner is not "added as a reviewer" of their own review, even via a team.
    recipients.remove(document.getOwnerId());
    // Adding is owner/admin-only and both act under their public name — no anonymity concern.
    String actorName =
        users.findById(event.actorId()).map(User::getDisplayName).orElse("An administrator");
    for (User recipient : deliverable(recipients, event.actorId())) {
      Map<String, Object> vars = baseVars(document, recipient);
      vars.put("actorName", actorName);
      vars.put("actionUrl", reviewUrl(document));
      mail.sendMailFromTemplate(
          MailTemplateKey.REVIEW_PARTICIPANT_ADDED, recipient.getEmail(), vars, null);
    }
  }

  private void annotationCreated(Document document, ReviewEvent.AnnotationCreated event) {
    if (annotations.findById(event.annotationId()).isEmpty()) {
      return;
    }
    String excerpt = firstCommentExcerpt(event.annotationId());
    for (User recipient : deliverable(Set.of(document.getOwnerId()), event.actorId())) {
      Map<String, Object> vars = baseVars(document, recipient);
      vars.put("actorName", actorNameFor(document, recipient, event.actorId()));
      vars.put("annotationExcerpt", excerpt);
      vars.put("actionUrl", annotationUrl(document, event.annotationId()));
      mail.sendMailFromTemplate(
          MailTemplateKey.REVIEW_ANNOTATION_CREATED, recipient.getEmail(), vars, null);
    }
  }

  private void annotationDecided(Document document, ReviewEvent.AnnotationDecided event) {
    Optional<Annotation> annotation = annotations.findById(event.annotationId());
    if (annotation.isEmpty()) {
      return;
    }
    Set<UUID> recipients =
        new LinkedHashSet<>(List.of(document.getOwnerId(), annotation.get().getAuthorId()));
    String excerpt = firstCommentExcerpt(event.annotationId());
    String decision = event.reopened() ? "reopened" : "resolved";
    for (User recipient : deliverable(recipients, event.actorId())) {
      Map<String, Object> vars = baseVars(document, recipient);
      vars.put("actorName", actorNameFor(document, recipient, event.actorId()));
      vars.put("annotationExcerpt", excerpt);
      vars.put("decision", decision);
      vars.put("actionUrl", annotationUrl(document, event.annotationId()));
      mail.sendMailFromTemplate(
          MailTemplateKey.REVIEW_ANNOTATION_DECIDED, recipient.getEmail(), vars, null);
    }
  }

  private void commentAdded(Document document, ReviewEvent.CommentAdded event) {
    Optional<Annotation> annotation = annotations.findById(event.annotationId());
    if (annotation.isEmpty()) {
      return;
    }
    // Slack-thread semantics: whoever started or joined the discussion follows it.
    Set<UUID> recipients = new LinkedHashSet<>();
    recipients.add(annotation.get().getAuthorId());
    List<Comment> thread = comments.findByAnnotationIdOrderByCreatedAtAsc(event.annotationId());
    thread.forEach(comment -> recipients.add(comment.getAuthorId()));
    String excerpt =
        thread.stream()
            .filter(comment -> comment.getId().equals(event.commentId()))
            .findFirst()
            .map(comment -> excerpt(comment.getBody()))
            .orElse("");
    for (User recipient : deliverable(recipients, event.actorId())) {
      Map<String, Object> vars = baseVars(document, recipient);
      vars.put("actorName", actorNameFor(document, recipient, event.actorId()));
      vars.put("commentExcerpt", excerpt);
      vars.put(
          "actionUrl",
          annotationUrl(document, event.annotationId()) + "&comment=" + event.commentId());
      mail.sendMailFromTemplate(
          MailTemplateKey.REVIEW_COMMENT_ADDED, recipient.getEmail(), vars, null);
    }
  }

  private void versionUploaded(Document document, ReviewEvent.VersionUploaded event) {
    if (event.versionNumber() <= 1) {
      // The first version IS the review's creation — participants joining later get
      // the invitation mail instead; there is no "new" version to announce.
      return;
    }
    // Uploads are owner-only and the owner acts under their public name (issue #413).
    String actorName =
        users.findById(event.actorId()).map(User::getDisplayName).orElse("The owner");
    for (User recipient : deliverable(reviewCircle(document), event.actorId())) {
      Map<String, Object> vars = baseVars(document, recipient);
      vars.put("actorName", actorName);
      vars.put("versionNumber", String.valueOf(event.versionNumber()));
      vars.put("actionUrl", reviewUrl(document) + "?version=" + event.versionNumber());
      mail.sendMailFromTemplate(
          MailTemplateKey.REVIEW_VERSION_UPLOADED, recipient.getEmail(), vars, null);
    }
  }

  private void workflowChanged(Document document, ReviewEvent.WorkflowChanged event) {
    if (!event.manual()) {
      // Derived IN_REVIEW ⇄ CHANGES_REQUESTED flips are announced by the annotation
      // mails that caused them — a second mail would say the same thing twice.
      return;
    }
    for (User recipient : deliverable(reviewCircle(document), event.actorId())) {
      Map<String, Object> vars = baseVars(document, recipient);
      vars.put("oldState", humanState(event.fromState()));
      vars.put("newState", humanState(event.toState()));
      vars.put("actionUrl", reviewUrl(document));
      mail.sendMailFromTemplate(
          MailTemplateKey.REVIEW_WORKFLOW_CHANGED, recipient.getEmail(), vars, null);
    }
  }

  /**
   * The users a mail may actually go to: the candidate set minus the actor, restricted to enabled
   * users with an address who have not opted out ({@link
   * UserSettingKey#EMAIL_REVIEW_NOTIFICATIONS}).
   */
  /** Everyone attached to the review: the owner, direct participants, and team members. */
  private Set<UUID> reviewCircle(Document document) {
    Set<UUID> recipients = new LinkedHashSet<>();
    recipients.add(document.getOwnerId());
    for (ReviewParticipant participant : participants.findByDocumentId(document.getId())) {
      if (participant.getUserId() != null) {
        recipients.add(participant.getUserId());
      } else if (participant.getTeamId() != null) {
        teamMembers.findMembersByTeamId(participant.getTeamId()).stream()
            .map(TeamMemberProjection::userId)
            .forEach(recipients::add);
      }
    }
    return recipients;
  }

  private List<User> deliverable(Set<UUID> candidates, UUID actorId) {
    Set<UUID> ids = new LinkedHashSet<>(candidates);
    ids.remove(actorId);
    if (ids.isEmpty()) {
      return List.of();
    }
    return users.findAllById(ids).stream()
        .filter(User::isEnabled)
        .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
        .filter(user -> !optedOut(user.getId()))
        .toList();
  }

  private boolean optedOut(UUID userId) {
    return userSettings
        .findByUserIdAndSettingKey(userId, UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS.getKey())
        .map(setting -> "false".equalsIgnoreCase(setting.getSettingValue()))
        .orElse(false);
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
