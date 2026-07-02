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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ExtractionStatus;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.job.JobHandler;
import io.qnop.service.storage.StorageService;
import io.qnop.spi.extract.DocumentExtractor;
import io.qnop.spi.extract.ExtractionException;
import io.qnop.spi.extract.RenderedDocument;
import io.qnop.spi.storage.StorageContent;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the async extraction of a {@code DocumentVersion} (issue #245, ADR-0032/0033): loads the
 * original from object storage, finds the {@link DocumentExtractor} for its content type, and
 * attaches the resulting {@link RenderedDocument} as jsonb — flipping the version's extraction
 * status to READY, or FAILED when the content itself is unprocessable.
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
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final DocumentVersionRepository versions;
  private final StorageService storage;
  private final List<DocumentExtractor> extractors;

  public DocumentExtractionJobHandler(
      DocumentVersionRepository versions,
      StorageService storage,
      List<DocumentExtractor> extractors) {
    this.versions = versions;
    this.storage = storage;
    this.extractors = extractors;
  }

  @Override
  public String type() {
    return DocumentIngestService.EXTRACTION_JOB_TYPE;
  }

  @Override
  public void handle(String payload) {
    UUID versionId = parseVersionId(payload);
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
      version.markExtractionFailed();
      versions.save(version);
      return;
    }

    try (StorageContent content =
        storage
            .get(version.getStorageKey())
            .orElseThrow(
                () ->
                    new IllegalStateException( // retryable: the object should exist post-commit
                        "stored object missing for version " + versionId))) {
      RenderedDocument rendered = extractor.get().extract(content.stream());
      version.attachRenderedDocument(MAPPER.writeValueAsString(rendered));
      versions.save(version);
    } catch (ExtractionException e) {
      log.warn("Extraction of version {} failed permanently: {}", versionId, e.getMessage());
      version.markExtractionFailed();
      versions.save(version);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize rendered document", e);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read stored object", e); // retryable I/O
    }
  }

  private static UUID parseVersionId(String payload) {
    try {
      JsonNode node = MAPPER.readTree(payload);
      return UUID.fromString(node.get("versionId").asText());
    } catch (JsonProcessingException | NullPointerException | IllegalArgumentException e) {
      // A malformed payload can only come from a code bug; retrying cannot fix it, but failing
      // loudly (job FAILED after max attempts) surfaces it instead of silently dropping work.
      throw new IllegalArgumentException("Malformed extraction payload: " + payload, e);
    }
  }
}
