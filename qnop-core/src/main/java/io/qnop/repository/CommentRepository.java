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
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for annotation comment threads (issue #244, ADR-0011). */
public interface CommentRepository extends JpaRepository<Comment, UUID> {

  /** The discussion thread of an annotation, oldest message first. */
  List<Comment> findByAnnotationIdOrderByCreatedAtAsc(UUID annotationId);

  /** The size of an annotation's thread (issue #247). */
  long countByAnnotationId(UUID annotationId);

  /**
   * Thread sizes for a set of annotations in one aggregation (issue #313) — annotations with no
   * comments are simply absent from the result, so the caller defaults them to 0.
   */
  @Query(
      "SELECT new io.qnop.repository.AnnotationCommentCount(c.annotationId, COUNT(c))"
          + " FROM Comment c WHERE c.annotationId IN :annotationIds GROUP BY c.annotationId")
  List<AnnotationCommentCount> countByAnnotationIdIn(
      @Param("annotationIds") Collection<UUID> annotationIds);
}
