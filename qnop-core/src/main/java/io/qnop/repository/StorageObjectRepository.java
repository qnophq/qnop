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

  /** Orphan-reaper query: rows in a given lifecycle state older than the cutoff. */
  List<StorageObject> findByStatusAndCreatedAtBefore(StorageObjectStatus status, Instant cutoff);

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
