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

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.job.JobService;
import io.qnop.service.storage.StagedObject;
import io.qnop.service.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Server-mediated document ingest (issue #245, ADR-0032 §5): validates the upload (magic-byte
 * sniffing, never the client-declared type; size against the operator setting), stages the binary
 * through the upload-then-commit storage layer (#243), and creates the {@code Document}/{@code
 * DocumentVersion} row <em>plus</em> its extraction job in one transaction (outbox, ADR-0033) — the
 * version and the job commit together or not at all. The staged object is committed only after that
 * transaction succeeds; a crash in between leaves an orphan the storage reaper reclaims.
 *
 * <p>Deliberately <em>not</em> {@code @Transactional} at the method level: {@link
 * StorageService#stage} must run outside the domain transaction so the PENDING registry row is
 * durable before the S3 write (see #243); only the domain writes run in the {@link
 * TransactionTemplate} block.
 */
@Service
public class DocumentIngestService {

  /** The job type consumed by {@code DocumentExtractionJobHandler}. */
  public static final String EXTRACTION_JOB_TYPE = "document.extract";

  static final String PDF_CONTENT_TYPE = "application/pdf";
  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
  private static final int MAX_TITLE_LENGTH = 500;

  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final StorageService storage;
  private final JobService jobs;
  private final ApplicationSettingsService settings;
  private final TransactionTemplate transactionTemplate;

  public DocumentIngestService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      StorageService storage,
      JobService jobs,
      ApplicationSettingsService settings,
      PlatformTransactionManager transactionManager) {
    this.documents = documents;
    this.versions = versions;
    this.storage = storage;
    this.jobs = jobs;
    this.settings = settings;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** Creates a new document owned by {@code actor} with the upload as version 1. */
  public UploadResult createDocument(UUID actor, String title, byte[] content) {
    String cleanTitle = requireTitle(title);
    validatePdf(content);

    StagedObject staged = storage.stage(new ByteArrayInputStream(content), PDF_CONTENT_TYPE);
    UploadResult result =
        transactionTemplate.execute(
            status -> {
              Document document = documents.save(new Document(actor, cleanTitle));
              DocumentVersion version = saveVersionAndEnqueue(document.getId(), 1, staged, actor);
              return new UploadResult(
                  document.getId(),
                  version.getVersionNumber(),
                  version.getExtractionStatus().name());
            });
    storage.commit(staged.key());
    return result;
  }

  /** Appends the upload as the next version of {@code documentId}; owner-only. */
  public UploadResult addVersion(UUID actor, UUID documentId, byte[] content) {
    Document document =
        documents
            .findById(documentId)
            .orElseThrow(
                () -> DocumentValidationException.notFound("no such document: " + documentId));
    if (!document.getOwnerId().equals(actor)) {
      throw DocumentValidationException.notOwner("only the owner may upload a new version");
    }
    validatePdf(content);

    StagedObject staged = storage.stage(new ByteArrayInputStream(content), PDF_CONTENT_TYPE);
    UploadResult result =
        transactionTemplate.execute(
            status -> {
              int next =
                  versions
                          .findTopByDocumentIdOrderByVersionNumberDesc(documentId)
                          .map(DocumentVersion::getVersionNumber)
                          .orElse(0)
                      + 1;
              DocumentVersion version = saveVersionAndEnqueue(documentId, next, staged, actor);
              return new UploadResult(
                  documentId, version.getVersionNumber(), version.getExtractionStatus().name());
            });
    storage.commit(staged.key());
    return result;
  }

  private DocumentVersion saveVersionAndEnqueue(
      UUID documentId, int versionNumber, StagedObject staged, UUID actor) {
    DocumentVersion version =
        versions.save(
            new DocumentVersion(
                documentId,
                versionNumber,
                staged.key(),
                staged.contentHash(),
                PDF_CONTENT_TYPE,
                staged.sizeBytes(),
                actor));
    // Outbox (ADR-0033): the job commits with the version row, so extraction can never be lost
    // for a version that exists, and never fires for one that rolled back.
    jobs.enqueue(EXTRACTION_JOB_TYPE, extractionPayload(version.getId()));
    return version;
  }

  /** The job payload; a tiny hand-built JSON object (single UUID — no mapper needed). */
  static String extractionPayload(UUID versionId) {
    return "{\"versionId\":\"" + versionId + "\"}";
  }

  private static String requireTitle(String title) {
    String trimmed = title == null ? "" : title.trim();
    if (trimmed.isEmpty()) {
      throw DocumentValidationException.invalidRequest("title is required");
    }
    if (trimmed.length() > MAX_TITLE_LENGTH) {
      throw DocumentValidationException.invalidRequest(
          "title exceeds " + MAX_TITLE_LENGTH + " characters");
    }
    return trimmed;
  }

  private void validatePdf(byte[] content) {
    if (content == null || content.length == 0) {
      throw DocumentValidationException.invalidRequest("empty upload");
    }
    long maxBytes =
        settings.getInteger(ApplicationSettingKey.UPLOAD_MAX_FILE_SIZE_MB) * 1024L * 1024L;
    if (content.length > maxBytes) {
      throw DocumentValidationException.tooLarge("document exceeds " + maxBytes + " bytes");
    }
    // Sniff the real type from the bytes (ADR-0032 §5); the client-declared MIME is never trusted.
    if (!hasPdfMagic(content)) {
      throw DocumentValidationException.unsupportedType("only PDF documents are accepted");
    }
  }

  private static boolean hasPdfMagic(byte[] content) {
    if (content.length < PDF_MAGIC.length) {
      return false;
    }
    for (int i = 0; i < PDF_MAGIC.length; i++) {
      if (content[i] != PDF_MAGIC[i]) {
        return false;
      }
    }
    return true;
  }

  /** Outcome of an upload: which document/version was created and its extraction state. */
  public record UploadResult(UUID documentId, int versionNumber, String extractionStatus) {}
}
