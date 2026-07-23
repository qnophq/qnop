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
package io.qnop.repository;

import io.qnop.entity.Comment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for annotation comment threads (issue #244, ADR-0011). */
public interface CommentRepository extends JpaRepository<Comment, UUID> {

  /** The discussion thread of an annotation, oldest message first. */
  List<Comment> findByAnnotationIdOrderByCreatedAtAsc(UUID annotationId);

  /**
   * The distinct users who commented on any of a document's threads (issue #413 pseudonyms) — a
   * comment-only participant never appears among the annotation authors, but still needs a stable
   * label. Joined through the annotation since comments carry only {@code annotationId}.
   */
  @Query(
      "SELECT DISTINCT c.authorId FROM Comment c, Annotation a"
          + " WHERE c.annotationId = a.id AND a.documentId = :documentId")
  List<UUID> findDistinctCommentAuthorIdsByDocumentId(@Param("documentId") UUID documentId);

  /** The opening message of an annotation's thread (issue #393); id breaks created_at ties. */
  Optional<Comment> findFirstByAnnotationIdOrderByCreatedAtAscIdAsc(UUID annotationId);

  /**
   * The opening message per annotation in one query (issue #393) — the tasks view's card title,
   * batched like the thread sizes (#313). Postgres {@code DISTINCT ON}; ties resolve by id.
   */
  @Query(
      value =
          "SELECT DISTINCT ON (annotation_id) annotation_id AS \"annotationId\", body"
              + " FROM comment WHERE annotation_id IN (:annotationIds)"
              + " ORDER BY annotation_id, created_at, id",
      nativeQuery = true)
  List<AnnotationFirstComment> findFirstByAnnotationIdIn(
      @Param("annotationIds") Collection<UUID> annotationIds);

  /** The size of an annotation's thread (issue #247). */
  long countByAnnotationId(UUID annotationId);

  /**
   * The global search's clauses (issue #540), shared by the opener and reply queries: the LIKE
   * match, the caller's review visibility (owner OR participant OR team-participant — the
   * findVisibleTo rule), and the ADR-0038 thread-visibility predicate (a PRIVATE review hides
   * foreign threads from anyone but the owner, the thread's author, or an admin) — so a discussion
   * hit never names, matches, or quotes a thread the caller cannot open.
   */
  String SEARCH_MATCH_CLAUSES =
      " FROM Comment c, Annotation a, Document d"
          + " WHERE c.annotationId = a.id AND a.documentId = d.id"
          + " AND LOWER(c.body) LIKE :q"
          + " AND (d.ownerId = :actor"
          + "   OR EXISTS (SELECT 1 FROM ReviewParticipant p"
          + "     WHERE p.documentId = d.id AND p.userId = :actor)"
          + "   OR EXISTS (SELECT 1 FROM ReviewParticipant pt, TeamMembership m"
          + "     WHERE pt.documentId = d.id AND pt.teamId = m.teamId AND m.userId = :actor))"
          + " AND (:admin = TRUE"
          + "   OR d.threadParticipation <> io.qnop.entity.ThreadParticipation.PRIVATE"
          + "   OR d.ownerId = :actor OR a.authorId = :actor)";

  /** A comment is its thread's OPENER when no earlier comment exists in the same thread. */
  String IS_OPENER_CLAUSE =
      " AND NOT EXISTS (SELECT 1 FROM Comment c2 WHERE c2.annotationId = c.annotationId"
          + "   AND (c2.createdAt < c.createdAt"
          + "     OR (c2.createdAt = c.createdAt AND c2.id < c.id)))";

  String SEARCH_MATCH_PROJECTION =
      "SELECT new io.qnop.repository.DiscussionMatchProjection("
          + "c.id, a.id, d.id, d.slug, d.title, a.status, c.body)";

  /**
   * Annotation hits of the global search (issue #540): matches in the OPENING text of annotation
   * threads — the annotation's own words (#301) — newest first. {@code q} pre-lowercased and {@code
   * LIKE}-wrapped.
   */
  @Query(
      value =
          SEARCH_MATCH_PROJECTION
              + SEARCH_MATCH_CLAUSES
              + IS_OPENER_CLAUSE
              + " ORDER BY c.createdAt DESC",
      countQuery = "SELECT COUNT(c)" + SEARCH_MATCH_CLAUSES + IS_OPENER_CLAUSE)
  Page<DiscussionMatchProjection> searchAnnotationOpeners(
      @Param("q") String q,
      @Param("actor") UUID actor,
      @Param("admin") boolean admin,
      Pageable pageable);

  /**
   * Comment hits of the global search (issue #540): matches in the REPLIES of annotation threads
   * (everything after the opener), newest first.
   */
  @Query(
      value =
          SEARCH_MATCH_PROJECTION
              + SEARCH_MATCH_CLAUSES
              + " AND EXISTS (SELECT 1 FROM Comment c2 WHERE c2.annotationId = c.annotationId"
              + "   AND (c2.createdAt < c.createdAt"
              + "     OR (c2.createdAt = c.createdAt AND c2.id < c.id)))"
              + " ORDER BY c.createdAt DESC",
      countQuery =
          "SELECT COUNT(c)"
              + SEARCH_MATCH_CLAUSES
              + " AND EXISTS (SELECT 1 FROM Comment c2 WHERE c2.annotationId = c.annotationId"
              + "   AND (c2.createdAt < c.createdAt"
              + "     OR (c2.createdAt = c.createdAt AND c2.id < c.id)))")
  Page<DiscussionMatchProjection> searchCommentReplies(
      @Param("q") String q,
      @Param("actor") UUID actor,
      @Param("admin") boolean admin,
      Pageable pageable);

  /** The newest comment by someone other than {@code viewer} (issue #307), single annotation. */
  Optional<Comment> findFirstByAnnotationIdAndAuthorIdNotOrderByCreatedAtDesc(
      UUID annotationId, UUID viewer);

  /**
   * The newest foreign comment time per annotation in one aggregation (issue #307) — the unseen
   * marker's input, batched like the thread sizes (#313). Annotations whose thread holds only the
   * viewer's own comments are absent from the result.
   */
  @Query(
      "SELECT new io.qnop.repository.AnnotationCommentActivity(c.annotationId, MAX(c.createdAt))"
          + " FROM Comment c WHERE c.annotationId IN :annotationIds AND c.authorId <> :viewer"
          + " GROUP BY c.annotationId")
  List<AnnotationCommentActivity> latestForeignActivityByAnnotationIdIn(
      @Param("annotationIds") Collection<UUID> annotationIds, @Param("viewer") UUID viewer);

  /**
   * Thread sizes for a set of annotations in one aggregation (issue #313) — annotations with no
   * comments are simply absent from the result, so the caller defaults them to 0.
   */
  @Query(
      "SELECT new io.qnop.repository.AnnotationCommentCount(c.annotationId, COUNT(c))"
          + " FROM Comment c WHERE c.annotationId IN :annotationIds GROUP BY c.annotationId")
  List<AnnotationCommentCount> countByAnnotationIdIn(
      @Param("annotationIds") Collection<UUID> annotationIds);

  /**
   * Replies directed at {@code viewer} for the dashboard (issue #454): comments by OTHERS on
   * annotations the viewer authored, or later than the viewer's own comment in threads they joined.
   * Newest first; the caller caps via {@code pageable}.
   */
  @Query(
      "SELECT c FROM Comment c, Annotation a WHERE a.id = c.annotationId"
          + " AND a.documentId IN :documentIds AND c.authorId <> :viewer"
          + " AND (a.authorId = :viewer OR EXISTS (SELECT 1 FROM Comment mine"
          + "   WHERE mine.annotationId = c.annotationId AND mine.authorId = :viewer"
          + "   AND mine.createdAt < c.createdAt))"
          + " ORDER BY c.createdAt DESC")
  List<Comment> repliesToViewer(
      @Param("documentIds") Collection<UUID> documentIds,
      @Param("viewer") UUID viewer,
      Pageable pageable);

  /** Comments the user wrote in NON-anonymous reviews (ADR-0038, issue #473). */
  @Query(
      "SELECT count(c) FROM Comment c, Annotation a, Document d"
          + " WHERE a.id = c.annotationId AND d.id = a.documentId"
          + " AND c.authorId = :authorId AND d.anonymous = false")
  long countPublicByAuthor(@Param("authorId") UUID authorId);
}
