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
import io.qnop.entity.Reaction;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.ReactionRepository;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.document.DocumentValidationException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slack-style emoji reactions on annotations and comments (issue #410). A reaction is a lightweight
 * signal, not a discussion contribution: reacting stays possible on RESOLVED annotations (the
 * closed-thread guard of #403 does not apply) but is refused once the review itself is
 * FINALIZED/CANCELLED. Reacting twice with the same emoji is a no-op, un-reacting something absent
 * likewise — the PUT/DELETE pair is idempotent. Reaction groups are aggregated per target and
 * batched onto the annotation and comment views (#313), with reactor names resolved through the
 * review's identities (issue #413) so anonymity holds.
 */
@Service
public class ReactionService {

  /** Storage cap; generous enough for ZWJ families, tight enough to reject prose. */
  static final int MAX_EMOJI_LENGTH = 32;

  private final ReactionRepository reactions;
  private final AnnotationRepository annotations;
  private final CommentRepository comments;
  private final DocumentAccessService documentAccess;

  public ReactionService(
      ReactionRepository reactions,
      AnnotationRepository annotations,
      CommentRepository comments,
      DocumentAccessService documentAccess) {
    this.reactions = reactions;
    this.annotations = annotations;
    this.comments = comments;
    this.documentAccess = documentAccess;
  }

  /** One grouped emoji on a target: the chip's count, own-state and reactor names. */
  public record ReactionGroup(
      String emoji, int count, boolean reactedByMe, List<String> reactors) {}

  /** Adds {@code actor}'s {@code emoji} to an annotation; a repeat is a no-op. */
  @Transactional
  public void reactToAnnotation(UUID annotationId, String emoji, UUID actor, boolean admin) {
    String validated = requireEmoji(emoji);
    Annotation annotation = requireAnnotation(annotationId);
    guardTarget(annotation, actor, admin);
    if (!reactions.existsByAnnotationIdAndUserIdAndEmoji(annotationId, actor, validated)) {
      reactions.save(Reaction.onAnnotation(annotationId, actor, validated));
    }
  }

  /** Removes {@code actor}'s {@code emoji} from an annotation; removing nothing is a no-op. */
  @Transactional
  public void unreactFromAnnotation(UUID annotationId, String emoji, UUID actor, boolean admin) {
    String validated = requireEmoji(emoji);
    Annotation annotation = requireAnnotation(annotationId);
    guardTarget(annotation, actor, admin);
    reactions.deleteByAnnotationIdAndUserIdAndEmoji(annotationId, actor, validated);
  }

  /** Adds {@code actor}'s {@code emoji} to a comment; a repeat is a no-op. */
  @Transactional
  public void reactToComment(UUID commentId, String emoji, UUID actor, boolean admin) {
    String validated = requireEmoji(emoji);
    Comment comment = requireComment(commentId);
    guardTarget(requireAnnotation(comment.getAnnotationId()), actor, admin);
    if (!reactions.existsByCommentIdAndUserIdAndEmoji(commentId, actor, validated)) {
      reactions.save(Reaction.onComment(commentId, actor, validated));
    }
  }

  /** Removes {@code actor}'s {@code emoji} from a comment; removing nothing is a no-op. */
  @Transactional
  public void unreactFromComment(UUID commentId, String emoji, UUID actor, boolean admin) {
    String validated = requireEmoji(emoji);
    Comment comment = requireComment(commentId);
    guardTarget(requireAnnotation(comment.getAnnotationId()), actor, admin);
    reactions.deleteByCommentIdAndUserIdAndEmoji(commentId, actor, validated);
  }

  /** Reaction groups for a batch of annotations in one query (#313); absent ids have none. */
  public Map<UUID, List<ReactionGroup>> forAnnotations(
      Collection<UUID> annotationIds,
      UUID viewer,
      ReviewIdentityResolver.ReviewIdentities identities) {
    if (annotationIds.isEmpty()) {
      return Map.of();
    }
    return groupByTarget(
        reactions.findByAnnotationIdInOrderByCreatedAtAsc(annotationIds),
        Reaction::getAnnotationId,
        viewer,
        identities);
  }

  /** Reaction groups for a batch of comments in one query (#313); absent ids have none. */
  public Map<UUID, List<ReactionGroup>> forComments(
      Collection<UUID> commentIds,
      UUID viewer,
      ReviewIdentityResolver.ReviewIdentities identities) {
    if (commentIds.isEmpty()) {
      return Map.of();
    }
    return groupByTarget(
        reactions.findByCommentIdInOrderByCreatedAtAsc(commentIds),
        Reaction::getCommentId,
        viewer,
        identities);
  }

  /**
   * Groups a target's reactions into chips — one group per emoji, in order of each emoji's FIRST
   * appearance (Slack's stable chip order), reactor names in reaction order. Pure and DB-free, so
   * the grouping is unit-testable on its own.
   */
  static List<ReactionGroup> group(
      List<Reaction> targetReactions, UUID viewer, Function<UUID, String> nameOf) {
    Map<String, List<Reaction>> byEmoji = new LinkedHashMap<>();
    for (Reaction reaction : targetReactions) {
      byEmoji
          .computeIfAbsent(reaction.getEmoji(), emoji -> new java.util.ArrayList<>())
          .add(reaction);
    }
    return byEmoji.entrySet().stream()
        .map(
            entry ->
                new ReactionGroup(
                    entry.getKey(),
                    entry.getValue().size(),
                    entry.getValue().stream().anyMatch(r -> viewer.equals(r.getUserId())),
                    entry.getValue().stream().map(r -> nameOf.apply(r.getUserId())).toList()))
        .toList();
  }

  /**
   * Accepts anything that plausibly IS an emoji and nothing that plausibly is prose: length-capped,
   * no whitespace/control characters, and at least one non-ASCII code point (keycap sequences like
   * "1️⃣" contain an ASCII digit but carry U+FE0F). Stored verbatim, so ZWJ families and skin tones
   * group exactly.
   */
  static String requireEmoji(String emoji) {
    if (emoji == null || emoji.isEmpty() || emoji.length() > MAX_EMOJI_LENGTH) {
      throw DocumentValidationException.invalidRequest("not an emoji");
    }
    boolean hasNonAscii = false;
    for (int i = 0; i < emoji.length(); ) {
      int codePoint = emoji.codePointAt(i);
      if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
        throw DocumentValidationException.invalidRequest("not an emoji");
      }
      hasNonAscii |= codePoint > 0x7F;
      i += Character.charCount(codePoint);
    }
    if (!hasNonAscii) {
      throw DocumentValidationException.invalidRequest("not an emoji");
    }
    return emoji;
  }

  private Map<UUID, List<ReactionGroup>> groupByTarget(
      List<Reaction> allReactions,
      Function<Reaction, UUID> targetOf,
      UUID viewer,
      ReviewIdentityResolver.ReviewIdentities identities) {
    Map<UUID, List<Reaction>> byTarget = new LinkedHashMap<>();
    for (Reaction reaction : allReactions) {
      byTarget
          .computeIfAbsent(targetOf.apply(reaction), id -> new java.util.ArrayList<>())
          .add(reaction);
    }
    Map<UUID, List<ReactionGroup>> grouped = new LinkedHashMap<>();
    byTarget.forEach(
        (targetId, targetReactions) ->
            grouped.put(targetId, group(targetReactions, viewer, identities::displayName)));
    return grouped;
  }

  /**
   * Participants only (404 otherwise, anti-enumeration), the PRIVATE thread-visibility rule of
   * #413, and the review-closed guard: a reaction is not a discussion contribution, so RESOLVED
   * annotations stay reactable — only FINALIZED/CANCELLED reviews refuse.
   */
  private void guardTarget(Annotation annotation, UUID actor, boolean admin) {
    DocumentAccessService.DocumentView document =
        documentAccess.getDocument(annotation.getDocumentId(), actor, admin);
    if (!AnnotationService.canSeeThread(document, annotation.getAuthorId(), actor, admin)) {
      throw DocumentValidationException.notFound("no such annotation: " + annotation.getId());
    }
    // String comparison — the column tolerates enterprise states (ADR-0011).
    String state = document.workflowState();
    if (WorkflowState.FINALIZED.name().equals(state)
        || WorkflowState.CANCELLED.name().equals(state)) {
      throw new WorkflowTransitionException(
          WorkflowTransitionException.REVIEW_CLOSED,
          "the review is " + state + "; reactions are closed");
    }
  }

  private Annotation requireAnnotation(UUID annotationId) {
    return annotations
        .findById(annotationId)
        .orElseThrow(() -> new AnnotationNotFoundException(annotationId));
  }

  private Comment requireComment(UUID commentId) {
    return comments
        .findById(commentId)
        .orElseThrow(() -> DocumentValidationException.notFound("no such comment: " + commentId));
  }
}
