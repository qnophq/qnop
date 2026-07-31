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
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for review aggregate roots (issue #244, ADR-0011).
 *
 * <p>Extends {@link JpaSpecificationExecutor} for the admin's moderation listing (issue #563),
 * whose facets are independent dimensions the caller combines freely — built as a {@code
 * Specification} rather than one query with a fistful of booleans.
 */
public interface DocumentRepository
    extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {

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
   *
   * <p>The two archive flags (issue #576) select the retention slice explicitly rather than through
   * one either/or boolean, because the callers need all three combinations: the overview shows the
   * active reviews ({@code true, false}), its Archived facet shows only the records ({@code false,
   * true}), and global search spans both ({@code true, true}) so an archived review stays findable.
   * Two plain booleans also keep the predicate free of a nullable parameter, which would need an
   * explicit {@code CAST} to compare against {@code NULL} on PostgreSQL.
   */
  @Query(
      "SELECT d FROM Document d WHERE (:q IS NULL OR LOWER(d.title) LIKE :q)"
          + " AND ((:includeActive = TRUE AND d.archivedAt IS NULL)"
          + "   OR (:includeArchived = TRUE AND d.archivedAt IS NOT NULL))"
          + " AND (d.ownerId = :actor"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant p"
          + "   WHERE p.documentId = d.id AND p.userId = :actor)"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant pt, TeamMembership m"
          + "   WHERE pt.documentId = d.id AND pt.teamId = m.teamId AND m.userId = :actor))")
  Page<Document> findVisibleTo(
      @Param("actor") UUID actor,
      @Param("q") String q,
      @Param("includeActive") boolean includeActive,
      @Param("includeArchived") boolean includeArchived,
      Pageable pageable);

  /**
   * Which of the given reviews the actor is actually part of (issue #563).
   *
   * <p>So the moderation listing can mark the rest. The same three ways in as {@link
   * #findVisibleTo} — owned, joined directly, joined through a team — because a row is only "not
   * yours" if none of them applies; a reviewer who joined via their team is participating even
   * though no row names them.
   */
  @Query(
      "SELECT d.id FROM Document d WHERE d.id IN :documentIds"
          + " AND (d.ownerId = :actor"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant p"
          + "   WHERE p.documentId = d.id AND p.userId = :actor)"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant pt, TeamMembership m"
          + "   WHERE pt.documentId = d.id AND pt.teamId = m.teamId AND m.userId = :actor))")
  List<UUID> findParticipatingIds(
      @Param("documentIds") Collection<UUID> documentIds, @Param("actor") UUID actor);

  /**
   * Reviews the given team participates in AND the actor may see (issue #586). The public team
   * profile's participation list is this intersection by construction, so it can never leak a
   * review the caller cannot reach (ADR-0038) — same visibility predicate as {@link
   * #findVisibleTo}, narrowed to the team's participations.
   */
  @Query(
      "SELECT d FROM Document d WHERE EXISTS (SELECT 1 FROM ReviewParticipant tp"
          + "   WHERE tp.documentId = d.id AND tp.teamId = :teamId)"
          + " AND (d.ownerId = :actor"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant p"
          + "   WHERE p.documentId = d.id AND p.userId = :actor)"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant pt, TeamMembership m"
          + "   WHERE pt.documentId = d.id AND pt.teamId = m.teamId AND m.userId = :actor))"
          + " ORDER BY d.updatedAt DESC")
  List<Document> findTeamParticipationsVisibleTo(
      @Param("teamId") UUID teamId, @Param("actor") UUID actor);

  /**
   * Everyone who owns a review, for the moderation listing's owner facet (issue #563).
   *
   * <p>Across the whole workspace rather than the current page: a dropdown offering only the owners
   * of the twenty rows on screen would look like the complete list and quietly not be one.
   * Ownership is structurally public (#413), so this leaks nothing a row would not.
   */
  @Query("SELECT DISTINCT d.ownerId FROM Document d")
  List<UUID> findDistinctOwnerIds(Pageable pageable);

  /** Reviews the user owns — ownership is structurally public, anonymous ones included (#473). */
  long countByOwnerId(UUID ownerId);

  /**
   * Closed-but-unarchived reviews whose terminal instant predates {@code cutoff} — the auto-archive
   * sweep's eligibility set (issue #576). Bounded by {@code Pageable} so one run can never load an
   * unbounded batch. {@code closed_at} is only ever set for FINALIZED/CANCELLED, so it doubles as
   * the closed-state predicate.
   */
  List<Document> findByArchivedAtIsNullAndClosedAtBefore(Instant cutoff, Pageable pageable);

  /**
   * How many reviews the sweep would archive at {@code cutoff} — the dry-run count (issue #576).
   */
  long countByArchivedAtIsNullAndClosedAtBefore(Instant cutoff);

  /**
   * Reviews archived before {@code cutoff} — the purge sweep's eligibility set (issue #577).
   * Bounded by {@code Pageable}: a purge batch is far more expensive than an archive batch (a
   * cascading delete plus object-storage work per review), so the bound matters more here.
   */
  List<Document> findByArchivedAtBefore(Instant cutoff, Pageable pageable);

  /** How many reviews the purge would delete at {@code cutoff} — the dry-run count (issue #577). */
  long countByArchivedAtBefore(Instant cutoff);
}
