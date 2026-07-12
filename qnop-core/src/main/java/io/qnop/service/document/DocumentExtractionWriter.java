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
package io.qnop.service.document;

import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ExtractionStatus;
import io.qnop.entity.PlacementStatus;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.job.JobEnqueuer;
import io.qnop.service.job.JobPayload;
import io.qnop.service.job.JobPayloadCodec;
import io.qnop.service.review.ReanchorJobHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The short, transactional write phase of document extraction (issue #314). Split out of {@link
 * DocumentExtractionJobHandler} so the S3 fetch and PDF parsing run with <em>no</em> DB transaction
 * held: the handler does the I/O, then calls one of these methods, each of which opens a brief
 * transaction just for the DB writes. Being a separate bean is what makes the
 * {@code @Transactional} boundary real — a self-invoked annotated method would bypass the proxy.
 *
 * <p>Every method re-loads the version by id and re-checks its status, so it is idempotent
 * (ADR-0033): a replay after a crash observes the already-terminal state and is a no-op. The
 * reanchor enqueue shares the READY write's transaction (the outbox, ADR-0033) so the follow-up job
 * can never be lost.
 */
@Component
class DocumentExtractionWriter {

  /** Audit type for a successful extraction; detail carries {@code {"versionId"}} (issue #325). */
  static final String AUDIT_EXTRACTION_SUCCEEDED = "extraction.succeeded";

  /**
   * Audit type for a permanently failed extraction; detail carries {@code {"versionId","reason"}}
   * (issue #325).
   */
  static final String AUDIT_EXTRACTION_FAILED = "extraction.failed";

  // Jackson 3 (tools.jackson) so the reason — arbitrary extractor text — is JSON-escaped rather
  // than concatenated into the detail string.
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final DocumentVersionRepository versions;
  private final AnnotationPlacementRepository placements;
  // The narrow write-side queue bean, injected directly (no ObjectProvider): depending on
  // JobEnqueuer rather than the full JobService keeps the extraction path from closing the
  // dispatch↔handler cycle, so the follow-up re-anchoring job (issue #248) enqueues without any
  // lazy indirection (issue #318).
  private final JobEnqueuer jobs;
  private final AuditEventRepository auditEvents;

  DocumentExtractionWriter(
      DocumentVersionRepository versions,
      AnnotationPlacementRepository placements,
      JobEnqueuer jobs,
      AuditEventRepository auditEvents) {
    this.versions = versions;
    this.placements = placements;
    this.jobs = jobs;
    this.auditEvents = auditEvents;
  }

  /**
   * Attaches the rendered representation, flips the version to READY, and — atomically, in the same
   * transaction (outbox) — enqueues re-anchoring when the version has pending placements. A version
   * that is already READY (a replay) is a no-op.
   */
  @Transactional
  public void attachRenderedAndChain(UUID versionId, String renderedJson) {
    DocumentVersion version = versions.findById(versionId).orElse(null);
    if (version == null || version.getExtractionStatus() == ExtractionStatus.READY) {
      return; // idempotent: deleted or already processed
    }
    version.attachRenderedDocument(renderedJson);
    versions.save(version);
    // System-generated (no actor): the async job, not a user, produced this. Commits with the
    // READY flip so the trail can never diverge from the version's state (issue #325).
    auditEvents.save(
        new AuditEvent(
            version.getDocumentId(), AUDIT_EXTRACTION_SUCCEEDED, null, detail(versionId, null)));
    if (!placements
        .findByDocumentVersionIdAndStatus(versionId, PlacementStatus.PENDING)
        .isEmpty()) {
      jobs.enqueue(
          ReanchorJobHandler.TYPE,
          JobPayloadCodec.serialize(new JobPayload.DocumentVersionRef(versionId)));
    }
  }

  /**
   * Marks the version FAILED permanently (unprocessable content or no extractor), records {@code
   * reason} for operators (issue #325), and fails its pending placements — they can never be
   * re-anchored against a version with no text layer. A version already FAILED (a replay) is a
   * no-op, so the failure is audited exactly once.
   */
  @Transactional
  public void failPermanently(UUID versionId, String reason) {
    DocumentVersion version = versions.findById(versionId).orElse(null);
    if (version == null || version.getExtractionStatus() == ExtractionStatus.FAILED) {
      return; // idempotent: deleted or already failed
    }
    version.markExtractionFailed(reason);
    versions.save(version);
    auditEvents.save(
        new AuditEvent(
            version.getDocumentId(), AUDIT_EXTRACTION_FAILED, null, detail(versionId, reason)));
    List<AnnotationPlacement> pending =
        placements.findByDocumentVersionIdAndStatus(versionId, PlacementStatus.PENDING);
    for (AnnotationPlacement placement : pending) {
      placement.markFailed();
      placements.save(placement);
    }
  }

  /** Builds the audit {@code detail} jsonb; {@code reason} is included only when present. */
  private static String detail(UUID versionId, String reason) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("versionId", versionId.toString());
    if (reason != null) {
      node.put("reason", reason);
    }
    return MAPPER.writeValueAsString(node);
  }
}
