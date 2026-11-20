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

import io.qnop.entity.StorageObject;
import io.qnop.entity.StorageObjectStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Data access for the object-storage staging registry (issue #243, ADR-0036). */
public interface StorageObjectRepository extends JpaRepository<StorageObject, UUID> {

  /** The registry row for a content-addressed key, if any (dedup + commit lookups). */
  Optional<StorageObject> findByObjectKey(String objectKey);

  /**
   * Commits the row of {@code objectKey} if it is still {@code PENDING}, as one conditional update
   * (issue #982). Identical uploads share one content-addressed row, so commits of it race: the
   * database serializes them on the row lock, and every commit after the first matches no row
   * instead of failing an optimistic-lock check. A row that is already {@code COMMITTED} keeps its
   * commit time and version. The version is bumped like a managed update would, so a managed write
   * of the row loaded while it was still {@code PENDING} fails its optimistic-lock check rather
   * than overwriting the commit.
   *
   * @return 1 if this call committed the row, 0 if it was already committed or is unknown
   */
  @Modifying
  @Query(
      "UPDATE StorageObject s SET s.status = :committed, s.committedAt = :at,"
          + " s.version = s.version + 1"
          + " WHERE s.objectKey = :objectKey AND s.status = :pending")
  int commitIfPending(
      @Param("objectKey") String objectKey,
      @Param("at") Instant at,
      @Param("pending") StorageObjectStatus pending,
      @Param("committed") StorageObjectStatus committed);

  /**
   * Marks the {@code PENDING} row of {@code objectKey} as staged just now, so an upload that reuses
   * an old row is not older than the reaper's grace period between its stage and its commit (issue
   * #987). For a {@code PENDING} row, {@code created_at} is therefore the time it was last staged.
   * Opens a transaction of its own when called outside one, as staging is. The version is bumped
   * like a managed update would.
   *
   * @return 1 if the row is still {@code PENDING} and was refreshed, 0 if it was committed or
   *     removed in the meantime
   */
  @Transactional
  @Modifying
  @Query(
      "UPDATE StorageObject s SET s.createdAt = :at, s.version = s.version + 1"
          + " WHERE s.objectKey = :objectKey AND s.status = :pending")
  int restageIfPending(
      @Param("objectKey") String objectKey,
      @Param("at") Instant at,
      @Param("pending") StorageObjectStatus pending);

  /** Orphan-reaper query: rows in a given lifecycle state older than the cutoff. */
  List<StorageObject> findByStatusAndCreatedAtBefore(StorageObjectStatus status, Instant cutoff);

  /**
   * Claims an orphan for the reaper by deleting its row, but only while it is still {@code PENDING}
   * and still older than the cutoff (issue #987). The reaper read the row earlier; an upload may
   * have committed it or staged it again since. The statement takes the row lock, so a concurrent
   * commit or restage waits for the reaper's transaction and then matches no row.
   *
   * @return 1 if this call removed the row, 0 if it is no longer an orphan
   */
  @Modifying
  @Query(
      "DELETE FROM StorageObject s"
          + " WHERE s.id = :id AND s.status = :pending AND s.createdAt < :cutoff")
  int deleteIfOrphaned(
      @Param("id") UUID id,
      @Param("cutoff") Instant cutoff,
      @Param("pending") StorageObjectStatus pending);

  /**
   * Every tracked object key (any lifecycle state), for the storage-consistency referenced set
   * (issue #523). Including PENDING rows keeps an in-flight, not-yet-committed upload from ever
   * looking like a bucket orphan.
   */
  @Query("SELECT s.objectKey FROM StorageObject s")
  List<String> findAllObjectKeys();

  /**
   * How many registry rows there are per lifecycle state and how many bytes they hold (issue #962),
   * as {@code [status, count, bytes]} rows — one grouped read for the product gauges.
   */
  @Query(
      "SELECT s.status, COUNT(s), COALESCE(SUM(s.sizeBytes), 0) FROM StorageObject s"
          + " GROUP BY s.status")
  List<Object[]> countAndSizeGroupedByStatus();
}
