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

import io.qnop.entity.Team;
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

/** Data access for {@link Team}. */
public interface TeamRepository extends JpaRepository<Team, UUID> {

  /** A team by case-insensitive name (matches the functional unique index). */
  Optional<Team> findByNameIgnoreCase(String name);

  boolean existsByNameIgnoreCase(String name);

  /**
   * Loads a team under a {@code PESSIMISTIC_WRITE} row lock (issue #470): serializes the team-lead
   * membership mutations so the "a team must keep at least one lead" guard cannot be bypassed by a
   * TOCTOU race — two concurrent demote/remove requests on *different* membership rows would each
   * read a stale lead count and both commit, leaving zero leads (optimistic {@code @Version} only
   * guards the individual rows). Taking this lock before the count funnels all such mutations for a
   * team through one at a time. Must be called inside a transaction.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM Team t WHERE t.id = :id")
  Optional<Team> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Paginated admin search (issue #105): an optional case-insensitive match on name or description.
   * {@code q} must be passed pre-lowercased and {@code LIKE}-wrapped (e.g. {@code %core%}); a
   * {@code null} {@code q} disables the filter.
   */
  @Query(
      "SELECT t FROM Team t WHERE :q IS NULL OR LOWER(t.name) LIKE :q"
          + " OR (t.description IS NOT NULL AND LOWER(t.description) LIKE :q)")
  Page<Team> search(@Param("q") String q, Pageable pageable);

  /**
   * Principal-directory search (issue #292): enabled teams by name. {@code q} pre-lowercased and
   * {@code LIKE}-wrapped; {@code null} disables the filter. Limit via {@code Pageable}.
   */
  @Query(
      "SELECT t FROM Team t WHERE t.enabled = TRUE AND (:q IS NULL OR LOWER(t.name) LIKE :q)"
          + " ORDER BY LOWER(t.name)")
  List<Team> searchEnabledPrincipals(@Param("q") String q, Pageable pageable);
}
