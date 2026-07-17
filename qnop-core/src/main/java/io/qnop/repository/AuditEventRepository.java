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

import io.qnop.entity.AuditEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for the append-only per-document audit trail (issue #244, ADR-0011). */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

  /** A document's audit trail, most recent first. */
  List<AuditEvent> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);

  /** The newest events of the given types across a set of documents (issue #454). */
  List<AuditEvent> findByDocumentIdInAndEventTypeInOrderByCreatedAtDesc(
      Collection<UUID> documentIds, Collection<String> eventTypes, Pageable pageable);

  /** How many events of one type landed after {@code since} (issue #454's weekly stat). */
  long countByDocumentIdInAndEventTypeAndCreatedAtAfter(
      Collection<UUID> documentIds, String eventType, Instant since);

  /**
   * The organisation-wide audit list for the AUDITOR/ADMIN view (issue #466, ADR-0041). Each filter
   * is optional (a {@code null} argument disables that predicate); ordering and paging come from
   * the {@link Pageable} (the service pins {@code createdAt DESC}). Deliberately un-scoped by
   * participation — the role gate is the only access control (ADR-0041).
   */
  @Query(
      "SELECT a FROM AuditEvent a WHERE (:eventType IS NULL OR a.eventType = :eventType) "
          + "AND (:actorId IS NULL OR a.actorId = :actorId) "
          + "AND (:documentId IS NULL OR a.documentId = :documentId) "
          + "AND (:from IS NULL OR a.createdAt >= :from) "
          + "AND (:to IS NULL OR a.createdAt <= :to)")
  Page<AuditEvent> findFiltered(
      @Param("eventType") String eventType,
      @Param("actorId") UUID actorId,
      @Param("documentId") UUID documentId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);
}
