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

import io.qnop.entity.CommentMention;
import io.qnop.entity.Document;
import io.qnop.repository.CommentMentionRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.spi.mention.MentionContext;
import io.qnop.spi.mention.MentionResolver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * for anyone off the roster stay plain text — mentioning someone without access does nothing.
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
  private final List<MentionResolver> resolvers;

  public CommentMentionService(
      CommentMentionRepository mentions,
      DocumentRepository documents,
      ReviewParticipantRepository participants,
      List<MentionResolver> resolvers) {
    this.mentions = mentions;
    this.documents = documents;
    this.participants = participants;
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
    UUID owner = document.getOwnerId();
    MentionContext context = new MentionContext(documentId, owner, authorId);
    Set<UUID> mentionedIds = new LinkedHashSet<>();
    for (String slug : slugs) {
      for (MentionResolver resolver : resolvers) {
        for (UUID id : resolveSafely(resolver, context, slug)) {
          if (hasAccess(documentId, owner, id)) {
            mentionedIds.add(id);
          }
        }
      }
    }
    List<CommentMention> rows =
        mentionedIds.stream().map(userId -> new CommentMention(commentId, userId)).toList();
    if (rows.isEmpty()) {
      return List.of();
    }
    mentions.saveAll(rows);
    return rows.stream().map(CommentMention::getMentionedUserId).toList();
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
