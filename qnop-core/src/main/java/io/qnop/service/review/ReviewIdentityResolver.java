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

import io.qnop.entity.Document;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.UserDisplayName;
import io.qnop.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Resolves who a review's annotation/comment authors are, honouring the document's per-review
 * anonymity (issue #413). Resolution is server-authoritative — anonymity that only lives in the
 * frontend is none, so an anonymous review never ships a resolvable identity for a non-self,
 * non-owner author.
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li><b>Non-anonymous</b> — every author resolves to their real display name. The name comes
 *       from a direct user lookup (an author is always a user, even when they participate via a
 *       team and so never appear among the participant rows).
 *   <li><b>Anonymous</b> — the signed-in caller sees their own contributions as themselves and the
 *       (structurally public) owner under their real name; every other author is a stable
 *       "Participant N" pseudonym. The exposed author id for such an author is a synthetic,
 *       per-document token derived from the pseudonym ordinal — never the real user id — so the
 *       client cannot correlate it back to the participant roster.
 * </ul>
 *
 * <p>The pseudonym ordinal is assigned by ascending user id over the document's non-owner users —
 * everyone who authored something <em>or</em> is a direct participant (issue #422), so the roster
 * and the annotations share one numbering: stable across requests and surfaces, deterministic, and
 * revealing nothing but a count.
 */
@Service
public class ReviewIdentityResolver {

  private final DocumentRepository documents;
  private final AnnotationRepository annotations;
  private final CommentRepository comments;
  private final ReviewParticipantRepository participants;
  private final UserRepository users;

  public ReviewIdentityResolver(
      DocumentRepository documents,
      AnnotationRepository annotations,
      CommentRepository comments,
      ReviewParticipantRepository participants,
      UserRepository users) {
    this.documents = documents;
    this.annotations = annotations;
    this.comments = comments;
    this.participants = participants;
    this.users = users;
  }

  /**
   * Builds the identity resolution for one review from {@code actor}'s perspective. Runs in the
   * caller's transaction; the document PK load hits the persistence context when the caller already
   * loaded it.
   */
  public ReviewIdentities forDocument(UUID documentId, UUID actor) {
    Document document =
        documents.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
    UUID ownerId = document.getOwnerId();

    // Every non-owner user who needs a stable label: annotation authors, comment-only authors, AND
    // direct participants who never wrote anything (issue #422) — one numbering across the roster
    // and the notes. Authors who participate via a team are covered by their authorship.
    Set<UUID> userIds = new LinkedHashSet<>();
    userIds.addAll(annotations.findDistinctAuthorIdsByDocumentId(documentId));
    userIds.addAll(comments.findDistinctCommentAuthorIdsByDocumentId(documentId));
    userIds.addAll(participants.findDirectUserIdsByDocumentId(documentId));

    Map<UUID, String> names =
        userIds.isEmpty()
            ? Map.of()
            : users.findDisplayNamesByIdIn(userIds).stream()
                .collect(Collectors.toMap(UserDisplayName::id, UserDisplayName::displayName));

    Map<UUID, Integer> ordinals = new HashMap<>();
    int next = 0;
    for (UUID id : userIds.stream().filter(id -> !id.equals(ownerId)).sorted().toList()) {
      ordinals.put(id, ++next);
    }

    return new ReviewIdentities(
        document.isAnonymous(), ownerId, actor, documentId, names, ordinals);
  }

  /** An immutable, request-scoped view of a review's author identities (issue #413). */
  public static final class ReviewIdentities {

    private final boolean anonymous;
    private final UUID ownerId;
    private final UUID actor;
    private final UUID documentId;
    private final Map<UUID, String> names;
    private final Map<UUID, Integer> ordinals;

    ReviewIdentities(
        boolean anonymous,
        UUID ownerId,
        UUID actor,
        UUID documentId,
        Map<UUID, String> names,
        Map<UUID, Integer> ordinals) {
      this.anonymous = anonymous;
      this.ownerId = ownerId;
      this.actor = actor;
      this.documentId = documentId;
      this.names = names;
      this.ordinals = ordinals;
    }

    /**
     * The display name to ship for {@code authorId}. Non-anonymous → the real name (or {@code null}
     * for a since-removed user, which the UI renders as a neutral placeholder). Anonymous → the
     * real name for the caller and the owner, a stable "Participant N" pseudonym for everyone else.
     */
    public String displayName(UUID authorId) {
      if (!anonymous || authorId.equals(actor) || authorId.equals(ownerId)) {
        return names.get(authorId);
      }
      Integer ordinal = ordinals.get(authorId);
      return "Participant " + (ordinal == null ? "?" : ordinal);
    }

    /**
     * The author id to expose on the wire. Unchanged for the caller's own and the owner's items
     * (both already-known identities) and in non-anonymous mode; a synthetic per-document token for
     * a foreign author under anonymity, so the real id never leaves the server.
     */
    public UUID exposedAuthorId(UUID authorId) {
      if (!anonymous || authorId.equals(actor) || authorId.equals(ownerId)) {
        return authorId;
      }
      int ordinal = ordinals.getOrDefault(authorId, 0);
      return UUID.nameUUIDFromBytes(
          (documentId + ":anon:" + ordinal).getBytes(StandardCharsets.UTF_8));
    }
  }
}
