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

import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.job.JobService;
import io.qnop.service.storage.StagedObject;
import io.qnop.service.storage.StorageQuotaExceededException;
import io.qnop.service.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
  private final DocumentAccessService access;
  private final AnnotationRepository annotations;
  private final AnnotationPlacementRepository placements;
  private final TransactionTemplate transactionTemplate;

  public DocumentIngestService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      StorageService storage,
      JobService jobs,
      ApplicationSettingsService settings,
      DocumentAccessService access,
      AnnotationRepository annotations,
      AnnotationPlacementRepository placements,
      PlatformTransactionManager transactionManager) {
    this.documents = documents;
    this.versions = versions;
    this.storage = storage;
    this.jobs = jobs;
    this.settings = settings;
    this.access = access;
    this.annotations = annotations;
    this.placements = placements;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Creates a new document owned by {@code actor} with the upload as version 1. An optional {@code
   * dueAt} completion deadline (issue #295) must be in the future when set at creation.
   */
  public UploadResult createDocument(UUID actor, String title, UploadSource upload, Instant dueAt) {
    String cleanTitle = requireTitle(title);
    Instant validDueAt = requireFutureOrNull(dueAt);
    long maxBytes = maxUploadBytes();
    validateUpload(upload, maxBytes);

    StagedObject staged = stageUpload(upload, maxBytes);
    UploadResult result =
        transactionTemplate.execute(
            status -> {
              Document document = new Document(actor, cleanTitle);
              document.setDueAt(validDueAt);
              document = documents.save(document);
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
  public UploadResult addVersion(UUID actor, UUID documentId, UploadSource upload) {
    Document document =
        documents
            .findById(documentId)
            .orElseThrow(
                () -> DocumentValidationException.notFound("no such document: " + documentId));
    if (!document.getOwnerId().equals(actor)) {
      // Anti-enumeration: a caller who cannot even see the document gets the same 404 as for an
      // unknown id; only a visible non-owner (participant/admin) learns the action is owner-only.
      if (!access.isVisible(documentId, actor, false)) {
        throw DocumentValidationException.notFound("no such document: " + documentId);
      }
      throw DocumentValidationException.notOwner("only the owner may upload a new version");
    }
    long maxBytes = maxUploadBytes();
    validateUpload(upload, maxBytes);

    StagedObject staged = stageUpload(upload, maxBytes);
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
              seedPendingPlacements(documentId, version);
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

  /**
   * Seeds a PENDING placement on the new version for every OPEN annotation (issue #248, ADR-0009),
   * carrying the annotation's most recent anchor as the re-anchoring hypothesis. Runs in the upload
   * transaction, so the version is never visible without its pending placements — which is exactly
   * what keeps it non-finalizable until re-anchoring resolves (ADR-0011). The re-anchor job itself
   * is enqueued by the extraction handler once the new text layer is READY.
   */
  private void seedPendingPlacements(UUID documentId, DocumentVersion newVersion) {
    var open = annotations.findByDocumentIdAndStatus(documentId, AnnotationStatus.OPEN);
    if (open.isEmpty()) {
      return;
    }
    Map<UUID, Integer> versionNumberById =
        versions.findByDocumentIdOrderByVersionNumberAsc(documentId).stream()
            .collect(Collectors.toMap(DocumentVersion::getId, DocumentVersion::getVersionNumber));
    for (Annotation annotation : open) {
      Optional<AnnotationPlacement> latest =
          placements.findByAnnotationId(annotation.getId()).stream()
              .filter(p -> !p.getDocumentVersionId().equals(newVersion.getId()))
              .max(
                  Comparator.comparing(
                      p -> versionNumberById.getOrDefault(p.getDocumentVersionId(), 0),
                      Comparator.naturalOrder()));
      latest.ifPresent(
          source ->
              placements.save(
                  new AnnotationPlacement(
                      annotation.getId(), newVersion.getId(), source.getAnchor())));
    }
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

  private static Instant requireFutureOrNull(Instant dueAt) {
    if (dueAt != null && !dueAt.isAfter(Instant.now())) {
      throw DocumentValidationException.invalidRequest("dueAt must be in the future");
    }
    return dueAt;
  }

  private long maxUploadBytes() {
    return settings.getInteger(ApplicationSettingKey.UPLOAD_MAX_FILE_SIZE_MB) * 1024L * 1024L;
  }

  /**
   * Rejects an upload before it is buffered: a fast early check on the (advisory) declared size,
   * then a streaming magic-byte sniff of the real type (ADR-0032 §5 — the client-declared MIME is
   * never trusted). The authoritative size limit is enforced while staging (issue #361).
   */
  private void validateUpload(UploadSource upload, long maxBytes) {
    if (upload.declaredSize() > maxBytes) {
      throw DocumentValidationException.tooLarge("document exceeds " + maxBytes + " bytes");
    }
    byte[] prefix = new byte[PDF_MAGIC.length];
    int read;
    try (InputStream in = upload.open()) {
      read = in.readNBytes(prefix, 0, prefix.length);
    } catch (IOException e) {
      throw DocumentValidationException.invalidRequest("upload could not be read");
    }
    if (read == 0) {
      throw DocumentValidationException.invalidRequest("empty upload");
    }
    if (read < PDF_MAGIC.length || !hasPdfMagic(prefix)) {
      throw DocumentValidationException.unsupportedType("only PDF documents are accepted");
    }
  }

  /** Streams the upload into storage under the authoritative size cap, mapping its errors. */
  private StagedObject stageUpload(UploadSource upload, long maxBytes) {
    try (InputStream in = upload.open()) {
      return storage.stage(in, PDF_CONTENT_TYPE, maxBytes);
    } catch (StorageQuotaExceededException e) {
      throw DocumentValidationException.tooLarge("document exceeds " + e.limitBytes() + " bytes");
    } catch (IOException e) {
      throw DocumentValidationException.invalidRequest("upload could not be read");
    }
  }

  private static boolean hasPdfMagic(byte[] prefix) {
    for (int i = 0; i < PDF_MAGIC.length; i++) {
      if (prefix[i] != PDF_MAGIC[i]) {
        return false;
      }
    }
    return true;
  }

  /** Outcome of an upload: which document/version was created and its extraction state. */
  public record UploadResult(UUID documentId, int versionNumber, String extractionStatus) {}
}
