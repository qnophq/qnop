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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

  /** The tombstone event type the prune trigger writes (keep in sync with 0040-audit-chain.sql). */
  public static final String PRUNED_EVENT_TYPE = "audit.chain.pruned";

  /**
   * The event type a user erasure records its redaction under (keep in sync with
   * 0042-audit-redaction.sql, issue #952).
   */
  public static final String REDACTED_EVENT_TYPE = "audit.chain.redacted";

  /** Event types that explain a document deletion (ReviewDeletionService, ReviewPurgeService). */
  static final String REVIEW_DELETED_EVENT_TYPE = "review.deleted";

  static final String REVIEW_PURGED_EVENT_TYPE = "review.purged";

  @PersistenceContext private EntityManager entityManager;

  /** Up to {@code limit} sealed rows after {@code afterSeq}, in chain order. */
  public List<AuditChainRow> findSealedAfter(long afterSeq, int limit) {
    List<?> raw =
        entityManager
            .createNativeQuery(
                """
                SELECT chain_seq, prev_hash, row_hash, id, scope, document_id, event_type,
                       actor_id, detail::text,
                       (extract(epoch FROM created_at) * 1000000)::bigint, redaction_id,
                       actor_pseudonym, (extract(epoch FROM redacted_at) * 1000000)::bigint
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
                    ((Number) r[9]).longValue(),
                    (UUID) r[10],
                    (String) r[11],
                    r[12] == null ? null : ((Number) r[12]).longValue()))
        .toList();
  }

  /**
   * Every tombstone event as raw detail text — parsed defensively by the verifier, so a malformed
   * tombstone is reported rather than aborting the walk.
   */
  public List<AuditChainTombstoneRow> findTombstones() {
    return findSystemEvents(PRUNED_EVENT_TYPE);
  }

  /**
   * Every {@code audit.chain.redacted} event (issue #952) as raw detail text, in chain order —
   * parsed defensively by the verifier like the tombstones.
   */
  public List<AuditChainTombstoneRow> findRedactions() {
    return findSystemEvents(REDACTED_EVENT_TYPE);
  }

  private List<AuditChainTombstoneRow> findSystemEvents(String eventType) {
    List<?> raw =
        entityManager
            .createNativeQuery(
                """
                SELECT chain_seq, detail::text
                FROM audit_event
                WHERE scope = 'SYSTEM' AND event_type = :type
                ORDER BY chain_seq
                """)
            .setParameter("type", eventType)
            .getResultList();
    return raw.stream()
        .map(Object[].class::cast)
        .map(
            r ->
                new AuditChainTombstoneRow(
                    r[0] == null ? null : ((Number) r[0]).longValue(), (String) r[1]))
        .toList();
  }

  /**
   * Ids of documents whose deletion the trail explains: named by a {@code review.deleted} event or
   * listed by a {@code review.purged} event (its uncapped {@code documentIds}, or the older capped
   * {@code purged[].id}). Every JSON access is type-guarded, so odd payloads are skipped, never
   * fatal.
   */
  public Set<String> findExplainedDocumentIds() {
    List<?> raw =
        entityManager
            .createNativeQuery(
                """
                SELECT x.detail ->> 'documentId'
                FROM audit_event x
                WHERE x.scope = 'SYSTEM' AND x.event_type = :deleted
                  AND jsonb_typeof(x.detail) = 'object'
                UNION
                SELECT d
                FROM audit_event x
                CROSS JOIN LATERAL jsonb_array_elements_text(
                  CASE WHEN jsonb_typeof(x.detail -> 'documentIds') = 'array'
                       THEN x.detail -> 'documentIds' ELSE '[]'::jsonb END) d
                WHERE x.scope = 'SYSTEM' AND x.event_type = :purged
                  AND jsonb_typeof(x.detail) = 'object'
                UNION
                SELECT p ->> 'id'
                FROM audit_event x
                CROSS JOIN LATERAL jsonb_array_elements(
                  CASE WHEN jsonb_typeof(x.detail -> 'purged') = 'array'
                       THEN x.detail -> 'purged' ELSE '[]'::jsonb END) p
                WHERE x.scope = 'SYSTEM' AND x.event_type = :purged
                  AND jsonb_typeof(x.detail) = 'object' AND jsonb_typeof(p) = 'object'
                """)
            .setParameter("deleted", REVIEW_DELETED_EVENT_TYPE)
            .setParameter("purged", REVIEW_PURGED_EVENT_TYPE)
            .getResultList();
    Set<String> ids = new HashSet<>();
    for (Object id : raw) {
      if (id instanceof String text) {
        ids.add(text);
      }
    }
    return ids;
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
   * Committed rows outside the chain: never sealed, or carrying an impossible position — only
   * possible when the triggers or constraints were bypassed.
   */
  public long countUnsealed() {
    return count("SELECT count(*) FROM audit_event WHERE chain_seq IS NULL OR chain_seq < 1");
  }

  /** All rows, for the verifier's cross-check against what the walk saw. */
  public long countAll() {
    return count("SELECT count(*) FROM audit_event");
  }

  private long count(String sql) {
    return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
  }
}
