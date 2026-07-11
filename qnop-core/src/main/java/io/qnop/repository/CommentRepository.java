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
}
