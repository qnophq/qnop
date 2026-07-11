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

import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ExtractionStatus;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.job.JobHandler;
import io.qnop.service.job.JobPayload;
import io.qnop.service.job.JobPayloadCodec;
import io.qnop.service.storage.StorageService;
import io.qnop.spi.extract.DocumentExtractor;
import io.qnop.spi.extract.ExtractionException;
import io.qnop.spi.extract.RenderedDocument;
import io.qnop.spi.storage.StorageContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the async extraction of a {@code DocumentVersion} (issue #245, ADR-0032/0033): loads the
 * original from object storage, finds the {@link DocumentExtractor} for its content type, and
 * attaches the resulting {@link RenderedDocument} as jsonb — flipping the version's extraction
 * status to READY, or FAILED when the content itself is unprocessable.
 *
 * <p><strong>Transaction shape (issue #314).</strong> The slow work — the S3 fetch and PDF parsing
 * — runs here with <em>no</em> DB transaction held (the job runner no longer wraps the handler, see
 * ADR-0033). Only the resulting DB writes are transactional, and they live in {@link
 * DocumentExtractionWriter} so the {@code @Transactional} boundary is a real proxy call. A network
 * hang or a 50 MB parse therefore no longer pins a pooled connection.
 *
 * <p><strong>Failure policy:</strong> {@link ExtractionException} (corrupt/encrypted content) and a
 * missing extractor are <em>permanent</em> — the handler marks the version FAILED and completes, so
 * the job is DONE and never retried (a re-upload creates a fresh version). Everything else
 * (storage/DB I/O) propagates and stays retryable under the queue's backoff.
 *
 * <p><strong>Idempotent</strong> (ADR-0033): a replay after a crash recomputes the same
 * representation deterministically; a version that is already READY or was deleted is a no-op.
 */
@Component
public class DocumentExtractionJobHandler implements JobHandler {

  private static final Logger log = LoggerFactory.getLogger(DocumentExtractionJobHandler.class);

  // Jackson 3 (tools.jackson) — the same stack Boot 4's MVC uses, so the stored jsonb and the
  // HTTP serialization of the published model never diverge in mapper behaviour.
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final DocumentVersionRepository versions;
  private final StorageService storage;
  private final List<DocumentExtractor> extractors;
  private final DocumentExtractionWriter writer;

  public DocumentExtractionJobHandler(
      DocumentVersionRepository versions,
      StorageService storage,
      List<DocumentExtractor> extractors,
      DocumentExtractionWriter writer) {
    this.versions = versions;
    this.storage = storage;
    this.extractors = extractors;
    this.writer = writer;
  }

  @Override
  public String type() {
    return DocumentIngestService.EXTRACTION_JOB_TYPE;
  }

  @Override
  public void handle(String payload) {
    UUID versionId =
        JobPayloadCodec.deserialize(payload, JobPayload.DocumentVersionRef.class).versionId();
    Optional<DocumentVersion> found = versions.findById(versionId);
    if (found.isEmpty()) {
      log.info("Extraction skipped: version {} no longer exists.", versionId);
      return; // idempotent: the document was deleted after enqueue
    }
    DocumentVersion version = found.get();
    if (version.getExtractionStatus() == ExtractionStatus.READY) {
      return; // idempotent: replay after a crash-past-the-work
    }

    Optional<DocumentExtractor> extractor =
        extractors.stream().filter(e -> e.supports(version.getContentType())).findFirst();
    if (extractor.isEmpty()) {
      // Permanent: no extractor can ever appear for this version's content type at runtime.
      log.warn(
          "No DocumentExtractor supports {} — marking version {} FAILED.",
          version.getContentType(),
          versionId);
      writer.failPermanently(
          versionId, "no extractor supports content type " + version.getContentType());
      return;
    }

    // The fetch + parse run with no DB transaction held (issue #314); only the write phase below
    // opens a short transaction.
    String renderedJson;
    try (StorageContent content =
        storage
            .get(version.getStorageKey())
            .orElseThrow(
                () ->
                    new IllegalStateException( // retryable: the object should exist post-commit
                        "stored object missing for version " + versionId))) {
      RenderedDocument rendered = extractor.get().extract(content.stream());
      renderedJson = MAPPER.writeValueAsString(rendered);
    } catch (ExtractionException e) {
      log.warn("Extraction of version {} failed permanently: {}", versionId, e.getMessage());
      writer.failPermanently(versionId, e.getMessage());
      return;
    } catch (JacksonException e) {
      // Serializing our own SPI records can only fail on a code bug; add context and let the
      // queue's retry/FAILED policy surface it.
      throw new IllegalStateException("Failed to serialize rendered document", e);
    }
    writer.attachRenderedAndChain(versionId, renderedJson);
  }
}
