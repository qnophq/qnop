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
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ExtractionStatus;
import io.qnop.entity.PlacementStatus;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.job.JobService;
import io.qnop.service.review.ReanchorJobHandler;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

  private final DocumentVersionRepository versions;
  private final AnnotationPlacementRepository placements;
  // ObjectProvider breaks the constructor cycle: JobService injects every JobHandler, and the
  // extraction path needs JobService back to enqueue the follow-up re-anchoring job (issue #248).
  private final ObjectProvider<JobService> jobs;

  DocumentExtractionWriter(
      DocumentVersionRepository versions,
      AnnotationPlacementRepository placements,
      ObjectProvider<JobService> jobs) {
    this.versions = versions;
    this.placements = placements;
    this.jobs = jobs;
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
    if (!placements
        .findByDocumentVersionIdAndStatus(versionId, PlacementStatus.PENDING)
        .isEmpty()) {
      jobs.getObject()
          .enqueue(ReanchorJobHandler.TYPE, DocumentIngestService.extractionPayload(versionId));
    }
  }

  /**
   * Marks the version FAILED permanently (unprocessable content or no extractor) and fails its
   * pending placements — they can never be re-anchored against a version with no text layer. A
   * version already FAILED (a replay) is a no-op.
   */
  @Transactional
  public void failPermanently(UUID versionId) {
    DocumentVersion version = versions.findById(versionId).orElse(null);
    if (version == null || version.getExtractionStatus() == ExtractionStatus.FAILED) {
      return; // idempotent: deleted or already failed
    }
    version.markExtractionFailed();
    versions.save(version);
    List<AnnotationPlacement> pending =
        placements.findByDocumentVersionIdAndStatus(versionId, PlacementStatus.PENDING);
    for (AnnotationPlacement placement : pending) {
      placement.markFailed();
      placements.save(placement);
    }
  }
}
