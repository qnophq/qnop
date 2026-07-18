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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Data access for the append-only per-document audit trail (issue #244, ADR-0011).
 *
 * <p>Extends {@link JpaSpecificationExecutor} for the organisation-wide, optionally-filtered
 * AUDITOR/ADMIN audit list (issue #466, ADR-0042): the filters are built as a {@code Specification}
 * in the service rather than a JPQL {@code (:param IS NULL OR …)} query, which PostgreSQL rejects
 * for a null timestamp bind parameter (it cannot infer the type of a parameter used only in {@code
 * ? IS NULL}).
 */
public interface AuditEventRepository
    extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

  /** A document's audit trail, most recent first. */
  List<AuditEvent> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);

  /** The newest events of the given types across a set of documents (issue #454). */
  List<AuditEvent> findByDocumentIdInAndEventTypeInOrderByCreatedAtDesc(
      Collection<UUID> documentIds, Collection<String> eventTypes, Pageable pageable);

  /** How many events of one type landed after {@code since} (issue #454's weekly stat). */
  long countByDocumentIdInAndEventTypeAndCreatedAtAfter(
      Collection<UUID> documentIds, String eventType, Instant since);
}
