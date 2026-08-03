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

import io.qnop.entity.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The in-app inbox's data access (issue #538, ADR-0051). Every read is scoped by {@code
 * recipientId} in the query itself rather than filtered afterwards, so there is no code path that
 * can return another user's notification by accident.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** One page of the recipient's inbox; the caller supplies the newest-first sort. */
  Page<Notification> findByRecipientId(UUID recipientId, Pageable pageable);

  /** The same page narrowed to unread ({@code readAt IS NULL}) or read notifications. */
  @Query(
      """
      SELECT n FROM Notification n
      WHERE n.recipientId = :recipientId
        AND ((:unread = TRUE AND n.readAt IS NULL) OR (:unread = FALSE AND n.readAt IS NOT NULL))
      """)
  Page<Notification> findByRecipientIdAndRead(
      @Param("recipientId") UUID recipientId, @Param("unread") boolean unread, Pageable pageable);

  /** The badge's number. */
  long countByRecipientIdAndReadAtIsNull(UUID recipientId);

  /**
   * Recipients with anything a digest could carry (issue #680).
   *
   * <p>Candidates, not the final set: cadence, timezone and whether today's digest already went out
   * are decided per recipient afterwards. Driving the run from this rather than from the user table
   * means an instance with ten thousand quiet accounts does no work for them.
   */
  @Query("SELECT DISTINCT n.recipientId FROM Notification n WHERE n.readAt IS NULL")
  List<UUID> findRecipientsWithUnread();

  /**
   * One recipient's unread notifications newer than their watermark, oldest first (issue #680).
   *
   * <p>Unread is the interaction with in-app: anything already read in the app is left out, which
   * is what makes the evening mail feel considerate rather than redundant.
   */
  @Query(
      "SELECT n FROM Notification n WHERE n.recipientId = :recipientId AND n.readAt IS NULL"
          + " AND n.createdAt > :after ORDER BY n.createdAt ASC")
  List<Notification> findUnreadForDigest(
      @Param("recipientId") UUID recipientId, @Param("after") Instant after);

  /** Scoped lookup — an id belonging to somebody else is simply absent (404, never 403). */
  Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

  /**
   * How many notifications the retention sweep would delete — the dry-run's number (issue #626).
   */
  long countByCreatedAtBefore(Instant cutoff);

  /**
   * Deletes at most {@code max} notifications older than {@code cutoff}, newest-first order
   * irrelevant (issue #626).
   *
   * <p>Native because JPQL has no {@code LIMIT} on {@code DELETE}, and the cap is the point: a
   * first run against a table that has grown for months must not become one enormous statement. The
   * subselect is served by {@code ix_notification_created_at} (changeset 0030).
   *
   * @return how many rows this batch actually removed — 0 means the sweep is done
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          DELETE FROM notification
          WHERE id IN (SELECT id FROM notification WHERE created_at < :cutoff LIMIT :max)
          """,
      nativeQuery = true)
  int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("max") int max);

  /**
   * Marks every unread notification of one recipient read in a single statement — "mark all read"
   * on a busy inbox must not load and dirty-check thousands of entities.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE Notification n SET n.readAt = :readAt
      WHERE n.recipientId = :recipientId AND n.readAt IS NULL
      """)
  int markAllRead(@Param("recipientId") UUID recipientId, @Param("readAt") Instant readAt);
}
