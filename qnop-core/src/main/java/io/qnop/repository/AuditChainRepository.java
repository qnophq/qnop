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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL reads of the audit hash chain (issue #928, ADR-0043). The chain columns ({@code
 * chain_seq}, {@code prev_hash}, {@code row_hash}) and {@code audit_chain_head} are written only by
 * database triggers and deliberately not mapped on {@link io.qnop.entity.AuditEvent}, so the
 * application can never write them through JPA.
 *
 * <p>Read-only: every method expects to run inside the verifier's (repeatable-read) transaction so
 * all of them see one snapshot.
 */
@Repository
public class AuditChainRepository {

  /** The tombstone event type the prune trigger writes (keep in sync with 0041-audit-chain.sql). */
  public static final String PRUNED_EVENT_TYPE = "audit.chain.pruned";

  @PersistenceContext private EntityManager entityManager;

  /** Up to {@code limit} sealed rows after {@code afterSeq}, in chain order. */
  public List<AuditChainRow> findSealedAfter(long afterSeq, int limit) {
    List<?> raw =
        entityManager
            .createNativeQuery(
                """
                SELECT chain_seq, prev_hash, row_hash, id, scope, document_id, event_type,
                       actor_id, detail::text,
                       (extract(epoch FROM created_at) * 1000000)::bigint
                FROM audit_event
                WHERE chain_seq > :after
                ORDER BY chain_seq
                LIMIT :limit
                """)
            .setParameter("after", afterSeq)
            .setParameter("limit", limit)
            .getResultList();
    return raw.stream()
        .map(Object[].class::cast)
        .map(
            r ->
                new AuditChainRow(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    (String) r[2],
                    (UUID) r[3],
                    (String) r[4],
                    (UUID) r[5],
                    (String) r[6],
                    (UUID) r[7],
                    (String) r[8],
                    ((Number) r[9]).longValue()))
        .toList();
  }

  /** Every pruned range recorded by a tombstone event. */
  public List<AuditChainPrunedRange> findPrunedRanges() {
    List<?> raw =
        entityManager
            .createNativeQuery(
                """
                SELECT e.chain_seq, (r ->> 'fromSeq')::bigint, (r ->> 'toSeq')::bigint,
                       r ->> 'prevHash', r ->> 'lastHash'
                FROM audit_event e
                CROSS JOIN LATERAL jsonb_array_elements(e.detail -> 'ranges') r
                WHERE e.scope = 'SYSTEM' AND e.event_type = :type
                  AND jsonb_typeof(e.detail -> 'ranges') = 'array'
                """)
            .setParameter("type", PRUNED_EVENT_TYPE)
            .getResultList();
    return raw.stream()
        .map(Object[].class::cast)
        .map(
            r ->
                new AuditChainPrunedRange(
                    r[0] == null ? null : ((Number) r[0]).longValue(),
                    ((Number) r[1]).longValue(),
                    ((Number) r[2]).longValue(),
                    (String) r[3],
                    (String) r[4]))
        .toList();
  }

  /** The chain head. */
  public AuditChainHead findHead() {
    Object[] r =
        (Object[])
            entityManager
                .createNativeQuery("SELECT last_seq, last_hash FROM audit_chain_head WHERE id = 1")
                .getSingleResult();
    return new AuditChainHead(((Number) r[0]).longValue(), (String) r[1]);
  }

  /**
   * Committed rows the commit-time seal never reached — only possible when the sealing trigger was
   * bypassed.
   */
  public long countUnsealed() {
    return ((Number)
            entityManager
                .createNativeQuery("SELECT count(*) FROM audit_event WHERE chain_seq IS NULL")
                .getSingleResult())
        .longValue();
  }
}
