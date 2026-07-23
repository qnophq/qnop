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

import io.qnop.entity.Document;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for review aggregate roots (issue #244, ADR-0011). */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

  /**
   * Loads a document under a {@code PESSIMISTIC_WRITE} row lock (issue #324): serializes the
   * operations that must observe a consistent pending-placement / READY-version picture against the
   * workflow transition — the finalize guard and a concurrent new-version upload — so a transition
   * cannot commit on a stale count. Must be called inside a transaction.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT d FROM Document d WHERE d.id = :id")
  Optional<Document> findByIdForUpdate(@Param("id") UUID id);

  /** Documents owned by the given user. */
  List<Document> findByOwnerId(UUID ownerId);

  /** Resolves a review by its human-readable slug (issue #411) — uniqueness is per LOWER(slug). */
  Optional<Document> findBySlugIgnoreCase(String slug);

  /** True when the slug is already claimed, ignoring case (issue #411). */
  boolean existsBySlugIgnoreCase(String slug);

  /**
   * The documents visible to a user for the reviews overview (issue #292): owned, joined as a
   * direct participant, or joined through membership in a participating team. {@code q} must be
   * passed pre-lowercased and {@code LIKE}-wrapped; {@code null} disables the title filter.
   */
  @Query(
      "SELECT d FROM Document d WHERE (:q IS NULL OR LOWER(d.title) LIKE :q)"
          + " AND (d.ownerId = :actor"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant p"
          + "   WHERE p.documentId = d.id AND p.userId = :actor)"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant pt, TeamMembership m"
          + "   WHERE pt.documentId = d.id AND pt.teamId = m.teamId AND m.userId = :actor))")
  Page<Document> findVisibleTo(@Param("actor") UUID actor, @Param("q") String q, Pageable pageable);

  /**
   * The global search's review query (issue #540): the same visibility rule as {@link
   * #findVisibleTo}, but matching the title OR the discussion (annotation/comment bodies) — the
   * latter only in threads the caller may see, mirroring {@code AnnotationService.canSeeThread}
   * (ADR-0038): only a PRIVATE review hides a foreign thread from anyone but the owner, the
   * thread's author, or an admin. {@code q} pre-lowercased and {@code LIKE}-wrapped, never null.
   */
  @Query(
      "SELECT d FROM Document d WHERE (d.ownerId = :actor"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant p"
          + "   WHERE p.documentId = d.id AND p.userId = :actor)"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant pt, TeamMembership m"
          + "   WHERE pt.documentId = d.id AND pt.teamId = m.teamId AND m.userId = :actor))"
          + " AND (LOWER(d.title) LIKE :q"
          + " OR EXISTS (SELECT 1 FROM Annotation a, Comment c"
          + "   WHERE a.documentId = d.id AND c.annotationId = a.id AND LOWER(c.body) LIKE :q"
          + "   AND (:admin = TRUE"
          + "     OR d.threadParticipation <> io.qnop.entity.ThreadParticipation.PRIVATE"
          + "     OR d.ownerId = :actor OR a.authorId = :actor)))")
  Page<Document> findVisibleToMatchingContent(
      @Param("actor") UUID actor,
      @Param("q") String q,
      @Param("admin") boolean admin,
      Pageable pageable);

  /** Reviews the user owns — ownership is structurally public, anonymous ones included (#473). */
  long countByOwnerId(UUID ownerId);
}
