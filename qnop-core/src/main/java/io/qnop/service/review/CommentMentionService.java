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
import io.qnop.entity.ThreadParticipation;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentMentionRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.spi.mention.MentionContext;
import io.qnop.spi.mention.MentionResolver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves and persists the @mentions of a comment (issue #462). Called from {@link
 * AnnotationService} right after a comment is saved; keeps all mention logic — parsing,
 * access-scoping and the anonymity policy — in one focused, testable place.
 *
 * <p><strong>Access-scoping:</strong> a token only becomes a mention when the mentioned user
 * actually has access to the document (owner, a direct participant, or a member of a participating
 * team — the same rule as {@link ReviewParticipantRepository#existsAccessibleParticipant}). Tokens
 * for anyone off the roster stay plain text — mentioning someone without access does nothing. Under
 * {@link ThreadParticipation#PRIVATE} the thread rule applies on top (issue #921): only users who
 * can see the comment's thread — the owner, the annotation's author, and admins on the roster (see
 * {@link ReviewAudience}) — can be mentioned there; any other token stays plain text, so no mention
 * row, no pill and no notification exist for someone who could not open the thread.
 *
 * <p><strong>Anonymity (ADR-0038, #413):</strong> mentions are disabled in anonymous reviews. A
 * roster picker or a rendered {@code @realname} would leak identities the review deliberately
 * hides, so no mention is resolved, persisted, or notified when {@link Document#isAnonymous()} —
 * the tokens simply remain plain text for everyone.
 *
 * <p><strong>Resolution seam (issue #598):</strong> the token→principal step is delegated to the
 * registered {@link MentionResolver}s (published in {@code qnop-spi}, ADR-0058). The Community
 * default is {@link UserSlugMentionResolver}; an add-on may contribute resolvers for other
 * namespaces (a team slug expanding to its members). Their answers are unioned, and the access rule
 * above is applied to every resolved id regardless of which resolver produced it — so a contributor
 * can name whom a token stands for but never widen who may be mentioned.
 */
@Service
public class CommentMentionService {

  private static final Logger log = LoggerFactory.getLogger(CommentMentionService.class);

  private final CommentMentionRepository mentions;
  private final DocumentRepository documents;
  private final ReviewParticipantRepository participants;
  private final CommentRepository comments;
  private final AnnotationRepository annotations;
  private final ReviewAudience audience;
  private final List<MentionResolver> resolvers;

  public CommentMentionService(
      CommentMentionRepository mentions,
      DocumentRepository documents,
      ReviewParticipantRepository participants,
      CommentRepository comments,
      AnnotationRepository annotations,
      ReviewAudience audience,
      List<MentionResolver> resolvers) {
    this.mentions = mentions;
    this.documents = documents;
    this.participants = participants;
    this.comments = comments;
    this.annotations = annotations;
    this.audience = audience;
    this.resolvers = List.copyOf(resolvers);
  }

  /**
   * Parses the {@code @slug} tokens of {@code body}, resolves each through the registered {@link
   * MentionResolver}s, keeps only mentions of users with access to {@code documentId}, and persists
   * a row per surviving mention. Returns the mentioned user ids that were persisted (for the
   * notification path to target); empty when the review is anonymous, the body has no tokens, or
   * none resolve to a user on the roster — an unknown slug stays plain text, exactly like a slug
   * without access.
   */
  @Transactional
  public List<UUID> resolveAndPersist(UUID commentId, UUID documentId, UUID authorId, String body) {
    Set<String> slugs = MentionParser.extractSlugs(body);
    if (slugs.isEmpty()) {
      return List.of();
    }
    Document document = documents.findById(documentId).orElse(null);
    if (document == null || document.isAnonymous()) {
      return List.of(); // anonymous reviews: mentions stay plain text (ADR-0038)
    }
    Set<UUID> mentionedIds = resolveAccessScoped(document, documentId, commentId, authorId, slugs);
    List<CommentMention> rows =
        mentionedIds.stream().map(userId -> new CommentMention(commentId, userId)).toList();
    if (rows.isEmpty()) {
      return List.of();
    }
    mentions.saveAll(rows);
    return rows.stream().map(CommentMention::getMentionedUserId).toList();
  }

  /**
   * Replays the resolution for an EDITED body (issue #600): the comment's mention rows are brought
   * in line with the re-parsed token set — rows whose token disappeared are deleted, rows that
   * survive keep their identity and timestamp, new ones are inserted — and only the ids that are
   * NEW relative to the previous set are returned. That delta is what an edit's notification path
   * needs: people mentioned before the edit were already told and must never be pinged again.
   *
   * <p>Anonymous reviews stay a no-op (ADR-0038): nothing was persisted at creation, and nothing is
   * touched now. A body that lost all its tokens drops every previous row. Survival is not purely
   * textual: resolution and the access check run again through the registered {@link
   * MentionResolver}s, so a row whose user meanwhile lost access is dropped even when the token
   * still stands — the same scoping policy creation applies.
   *
   * <p>{@code commentId} must be a comment of {@code documentId} — the caller resolves that
   * ownership (it loaded the comment to edit it); this service does not re-verify it.
   *
   * <p><strong>Concurrency contract:</strong> two overlapping calls for the same comment can both
   * compute the same "new" row, and the second insert then hits the unique comment/user constraint.
   * The caller (the enterprise edit endpoint) must serialize edits per comment — which it needs
   * anyway for the body itself — rather than this service absorbing constraint violations.
   */
  @Transactional
  public List<UUID> reResolveAndPersist(
      UUID commentId, UUID documentId, UUID authorId, String body) {
    Document document = documents.findById(documentId).orElse(null);
    if (document == null || document.isAnonymous()) {
      return List.of();
    }
    Set<UUID> wanted =
        resolveAccessScoped(
            document, documentId, commentId, authorId, MentionParser.extractSlugs(body));
    List<CommentMention> existing = mentions.findByCommentId(commentId);
    Set<UUID> before =
        existing.stream().map(CommentMention::getMentionedUserId).collect(Collectors.toSet());
    List<CommentMention> stale =
        existing.stream().filter(row -> !wanted.contains(row.getMentionedUserId())).toList();
    if (!stale.isEmpty()) {
      mentions.deleteAll(stale);
    }
    List<CommentMention> added =
        wanted.stream()
            .filter(userId -> !before.contains(userId))
            .map(userId -> new CommentMention(commentId, userId))
            .toList();
    if (added.isEmpty()) {
      return List.of();
    }
    mentions.saveAll(added);
    return added.stream().map(CommentMention::getMentionedUserId).toList();
  }

  /**
   * The ids the tokens address after resolver union and the document-access filter — the one
   * resolution path shared by creation and re-resolution (issue #600), so the two can never drift.
   */
  private Set<UUID> resolveAccessScoped(
      Document document, UUID documentId, UUID commentId, UUID authorId, Set<String> slugs) {
    UUID owner = document.getOwnerId();
    MentionContext context = new MentionContext(documentId, owner, authorId);
    boolean privateThreads = document.getThreadParticipation() == ThreadParticipation.PRIVATE;
    UUID threadAuthor = privateThreads ? threadAuthorOf(commentId) : null;
    Set<UUID> mentionedIds = new LinkedHashSet<>();
    for (String slug : slugs) {
      for (MentionResolver resolver : resolvers) {
        for (UUID id : resolveSafely(resolver, context, slug)) {
          boolean allowed =
              privateThreads
                  ? audience.canSeeThread(document, threadAuthor, id, false)
                  : hasAccess(documentId, owner, id);
          if (allowed) {
            mentionedIds.add(id);
          }
        }
      }
    }
    return mentionedIds;
  }

  /** The author of the thread {@code commentId} belongs to, or null when it cannot be found. */
  private UUID threadAuthorOf(UUID commentId) {
    return comments
        .findById(commentId)
        .map(Comment::getAnnotationId)
        .flatMap(annotations::findById)
        .map(Annotation::getAuthorId)
        .orElse(null);
  }

  /**
   * One resolver's answer, defended: a contributed resolver that throws or returns null must
   * degrade to "no mention" for its namespace, never abort the comment/annotation transaction the
   * mention resolution rides in (review finding on issue #598, tracked in #795).
   */
  private Set<UUID> resolveSafely(MentionResolver resolver, MentionContext context, String slug) {
    try {
      Set<UUID> resolved = resolver.resolve(context, slug);
      return resolved == null ? Set.of() : resolved;
    } catch (RuntimeException e) {
      log.warn(
          "MentionResolver {} failed for a token — treating as unresolved.",
          resolver.getClass().getName(),
          e);
      return Set.of();
    }
  }

  private boolean hasAccess(UUID documentId, UUID owner, UUID userId) {
    return userId.equals(owner) || participants.existsAccessibleParticipant(documentId, userId);
  }
}
