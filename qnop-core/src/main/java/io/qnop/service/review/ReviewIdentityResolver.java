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
import io.qnop.repository.ReviewPseudonymRepository;
import io.qnop.repository.UserDisplayName;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserSlug;
import io.qnop.security.PseudonymKeyService;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>The pseudonym ordinal covers the document's non-owner principals — everyone who authored
 * something <em>or</em> is a direct participant (issue #422), so the roster and the annotations
 * share one numbering. Ordinals are persisted write-once in {@code review_pseudonym} (issue #914,
 * ADR-0038 amendment): a principal without one is appended after the current maximum, newcomers
 * ordered among themselves by a keyed HMAC ({@link PseudonymKeyService}) — never by user id, which
 * is time-ordered and public. So a label is neither derivable from ids nor shifted by later joins.
 *
 * <p><b>Side effect — keep in mind when calling this from a new path.</b> When {@link #forDocument}
 * runs inside a <em>writable</em> transaction and a principal has no ordinal yet, it allocates one:
 * in a short, separate {@code REQUIRES_NEW} transaction under a per-review advisory lock, committed
 * immediately, so no lock or uncommitted row outlives the allocation (the caller may go on to do
 * slow work such as sending mail). Every contribution path (annotation, comment) runs writable and
 * so persists its author's label. A read-only caller never writes; it sees the deterministic
 * provisional ordinal the next writer will persist.
 */
@Service
public class ReviewIdentityResolver {

  private final DocumentRepository documents;
  private final AnnotationRepository annotations;
  private final CommentRepository comments;
  private final ReviewParticipantRepository participants;
  private final UserRepository users;
  private final ReviewPseudonymRepository pseudonyms;
  private final PseudonymKeyService pseudonymKeys;
  private final TransactionTemplate allocationTx;

  public ReviewIdentityResolver(
      DocumentRepository documents,
      AnnotationRepository annotations,
      CommentRepository comments,
      ReviewParticipantRepository participants,
      UserRepository users,
      ReviewPseudonymRepository pseudonyms,
      PseudonymKeyService pseudonymKeys,
      PlatformTransactionManager transactionManager) {
    this.documents = documents;
    this.annotations = annotations;
    this.comments = comments;
    this.participants = participants;
    this.users = users;
    this.pseudonyms = pseudonyms;
    this.pseudonymKeys = pseudonymKeys;
    this.allocationTx = new TransactionTemplate(transactionManager);
    this.allocationTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    // Every non-owner user who needs a stable label: annotation authors, comment-only authors,
    // reactors, AND direct participants who never wrote anything (issue #422) — one numbering
    // across
    // the roster and the notes. Team-derived reviewers are covered by what they did.
    Set<UUID> userIds = new LinkedHashSet<>();
    userIds.addAll(annotations.findDistinctAuthorIdsByDocumentId(documentId));
    userIds.addAll(comments.findDistinctCommentAuthorIdsByDocumentId(documentId));
    userIds.addAll(participants.findDirectUserIdsByDocumentId(documentId));
    // The owner is structurally public (#413) but is no participant row and may
    // not have written anything yet — without this, owner-driven events (workflow
    // transitions, reviewer additions) resolve to a null name (issue #472).
    userIds.add(ownerId);

    Map<UUID, String> names =
        userIds.isEmpty()
            ? new HashMap<>()
            : users.findDisplayNamesByIdIn(userIds).stream()
                .collect(
                    Collectors.toMap(
                        UserDisplayName::id,
                        UserDisplayName::displayName,
                        (a, b) -> a,
                        HashMap::new));
    // Account-less participants (issue #684): their row id is their principal id and their
    // display name lives on the row. They number into the same ordinal space as everyone else —
    // an anonymous review pseudonymizes a guest exactly like a user (ADR-0038).
    for (var external : participants.findExternalByDocumentId(documentId)) {
      userIds.add(external.getId());
      names.put(external.getId(), external.getExternalDisplayName());
    }
    // Profile slugs for pretty profile links (issue #486); shipped under the
    // exact same exposure rule as the real author id.
    Map<UUID, String> slugs =
        userIds.isEmpty()
            ? Map.of()
            : users.findSlugsByIdIn(userIds).stream()
                .filter(row -> row.slug() != null)
                .collect(Collectors.toMap(UserSlug::id, UserSlug::slug));

    // Ordinals only matter under anonymity; non-anonymous reviews never read them.
    Map<UUID, Integer> ordinals =
        document.isAnonymous()
            ? ordinals(documentId, userIds.stream().filter(id -> !id.equals(ownerId)).toList())
            : Map.of();

    return new ReviewIdentities(
        document.isAnonymous(), ownerId, actor, documentId, names, slugs, ordinals);
  }

  /**
   * The ordinals of {@code principals}: persisted ones as stored, missing ones allocated (when the
   * caller's transaction is writable) or provisional (when it is not).
   *
   * <p>Allocation happens only when something is missing — the hot path is one read. It runs in its
   * own short {@code REQUIRES_NEW} transaction that takes a per-review {@code
   * pg_advisory_xact_lock}, re-reads the committed assignments, inserts the missing ones in ordinal
   * order and commits. So concurrent first contributions to one review are serialized instead of
   * racing on the unique keys, and the caller's transaction holds neither the lock nor uncommitted
   * rows. A label survives a rolled-back contribution, which is harmless: it is never reused and
   * reveals nothing. The table deliberately has no FK to the document, so the allocation never
   * waits on a document row the caller holds locked or has not committed yet (migration 0038).
   */
  private Map<UUID, Integer> ordinals(UUID documentId, Collection<UUID> principals) {
    Function<UUID, byte[]> sortKey = id -> pseudonymKeys.sortKey(documentId, id);
    Map<UUID, Integer> assigned = loadAssigned(documentId);
    if (assigned.keySet().containsAll(principals) || !canWrite()) {
      return PseudonymOrdinals.extend(assigned, principals, sortKey);
    }
    List<UUID> wanted = List.copyOf(principals);
    return allocationTx.execute(status -> allocate(documentId, wanted, sortKey));
  }

  /** Runs inside the allocation transaction; see {@link #ordinals}. */
  private Map<UUID, Integer> allocate(
      UUID documentId, List<UUID> principals, Function<UUID, byte[]> sortKey) {
    pseudonyms.lockForAllocation("review_pseudonym:" + documentId);
    Map<UUID, Integer> assigned = loadAssigned(documentId);
    Map<UUID, Integer> extended = PseudonymOrdinals.extend(assigned, principals, sortKey);
    extended.entrySet().stream()
        .filter(entry -> !assigned.containsKey(entry.getKey()))
        .sorted(Map.Entry.comparingByValue())
        .forEach(
            entry ->
                pseudonyms.insertIfAbsent(
                    UUID.randomUUID(), documentId, entry.getKey(), entry.getValue()));
    return extended;
  }

  private Map<UUID, Integer> loadAssigned(UUID documentId) {
    Map<UUID, Integer> assigned = new HashMap<>();
    for (var row : pseudonyms.findByDocumentId(documentId)) {
      assigned.put(row.getPrincipalId(), row.getOrdinal());
    }
    return assigned;
  }

  private static boolean canWrite() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
  }

  /** An immutable, request-scoped view of a review's author identities (issue #413). */
  public static final class ReviewIdentities {

    private final boolean anonymous;
    private final UUID ownerId;
    private final UUID actor;
    private final UUID documentId;
    private final Map<UUID, String> names;
    private final Map<UUID, String> slugs;
    private final Map<UUID, Integer> ordinals;

    ReviewIdentities(
        boolean anonymous,
        UUID ownerId,
        UUID actor,
        UUID documentId,
        Map<UUID, String> names,
        Map<UUID, String> slugs,
        Map<UUID, Integer> ordinals) {
      this.anonymous = anonymous;
      this.ownerId = ownerId;
      this.actor = actor;
      this.documentId = documentId;
      this.names = names;
      this.slugs = slugs;
      this.ordinals = ordinals;
    }

    /** Whether {@code userId} is the signed-in caller — used to sort the caller's own row first. */
    public boolean isSelf(UUID userId) {
      return userId != null && userId.equals(actor);
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

    /**
     * The profile slug to ship for {@code authorId} (issue #486) — pretty profile links, under the
     * SAME exposure rule as {@link #exposedAuthorId}: a slug identifies as surely as the id, so a
     * pseudonymised author ships {@code null} (as does a user without a slug).
     */
    public String slug(UUID authorId) {
      if (!anonymous || authorId.equals(actor) || authorId.equals(ownerId)) {
        return slugs.get(authorId);
      }
      return null;
    }
  }
}
