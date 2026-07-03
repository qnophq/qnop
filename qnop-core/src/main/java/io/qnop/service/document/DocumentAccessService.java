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

import io.qnop.api.v1.model.RenderedDocumentResponse;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ExtractionStatus;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.service.storage.StorageService;
import io.qnop.spi.storage.StorageContent;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authorized read access to documents, their versions, the rendered representation, and the
 * original binary (issue #245, ADR-0032 §5). A document is visible to its owner, to every review
 * participant (directly or via team membership), and to a global admin; everyone else gets a 404 —
 * never a 403 — so document ids are not enumerable. Serving is server-mediated with this
 * per-request check; presigned URLs are deliberately not offered.
 *
 * <p>Keeps all entities inside the service layer (ADR-0004): results cross the boundary as
 * entity-free views or as the published {@code qnop-api} model.
 */
@Service
public class DocumentAccessService {

  // Jackson 3 (tools.jackson), matching the stack Boot 4's MVC serializes responses with.
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final ReviewParticipantRepository participants;
  private final StorageService storage;

  public DocumentAccessService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      ReviewParticipantRepository participants,
      StorageService storage) {
    this.documents = documents;
    this.versions = versions;
    this.participants = participants;
    this.storage = storage;
  }

  /** The document's metadata, if visible to {@code actor}. */
  @Transactional(readOnly = true)
  public DocumentView getDocument(UUID documentId, UUID actor, boolean admin) {
    Document document = requireVisible(documentId, actor, admin);
    int latest =
        versions
            .findTopByDocumentIdOrderByVersionNumberDesc(documentId)
            .map(DocumentVersion::getVersionNumber)
            .orElse(0);
    return new DocumentView(
        document.getId(),
        document.getTitle(),
        document.getOwnerId(),
        document.getWorkflowState(),
        latest,
        document.getCreatedAt(),
        document.getUpdatedAt(),
        document.getDueAt());
  }

  /** All versions of a visible document, oldest first. */
  @Transactional(readOnly = true)
  public List<DocumentVersionView> listVersions(UUID documentId, UUID actor, boolean admin) {
    requireVisible(documentId, actor, admin);
    return versions.findByDocumentIdOrderByVersionNumberAsc(documentId).stream()
        .map(
            v ->
                new DocumentVersionView(
                    v.getVersionNumber(),
                    v.getContentType(),
                    v.getSizeBytes(),
                    v.getContentHash(),
                    v.getExtractionStatus().name(),
                    v.getCreatedBy(),
                    v.getCreatedAt()))
        .toList();
  }

  /**
   * The canonical rendered representation of one version (ADR-0032), parsed into the published API
   * model — proving on every read that the stored payload matches the contract. 409 while
   * extraction is pending or after it failed.
   */
  @Transactional(readOnly = true)
  public RenderedDocumentResponse getRendered(
      UUID documentId, int versionNumber, UUID actor, boolean admin) {
    requireVisible(documentId, actor, admin);
    DocumentVersion version = requireVersion(documentId, versionNumber);
    if (version.getExtractionStatus() == ExtractionStatus.FAILED) {
      throw DocumentValidationException.renderingUnavailable(
          "EXTRACTION_FAILED", "extraction failed for this version; upload a new version");
    }
    if (version.getExtractionStatus() != ExtractionStatus.READY
        || version.getRenderedDocument() == null) {
      throw DocumentValidationException.renderingUnavailable(
          "EXTRACTION_PENDING", "extraction has not completed yet");
    }
    try {
      return MAPPER.readValue(version.getRenderedDocument(), RenderedDocumentResponse.class);
    } catch (JacksonException e) {
      throw new IllegalStateException(
          "stored rendered document does not match the API contract for version " + version.getId(),
          e);
    }
  }

  /**
   * The original binary of one version, streamed from object storage. Caller closes the content.
   */
  @Transactional(readOnly = true)
  public OriginalDownload getOriginal(
      UUID documentId, int versionNumber, UUID actor, boolean admin) {
    Document document = requireVisible(documentId, actor, admin);
    DocumentVersion version = requireVersion(documentId, versionNumber);
    StorageContent content =
        storage
            .get(version.getStorageKey())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "stored object missing for version " + version.getId()));
    return new OriginalDownload(
        document.getTitle(),
        version.getVersionNumber(),
        version.getContentHash(),
        content.stream(),
        content.contentLength(),
        content.contentType());
  }

  private Document requireVisible(UUID documentId, UUID actor, boolean admin) {
    Document document =
        documents
            .findById(documentId)
            .orElseThrow(
                () -> DocumentValidationException.notFound("no such document: " + documentId));
    if (!canAccess(document, actor, admin)) {
      // 404, not 403: non-participants must not learn that the document exists.
      throw DocumentValidationException.notFound("no such document: " + documentId);
    }
    return document;
  }

  /**
   * Whether {@code actor} may see {@code documentId} at all (owner, participant — direct or via
   * team — or admin). Used by the ingest path to distinguish an invisible document (404) from a
   * visible one where the action is owner-only (403).
   */
  @Transactional(readOnly = true)
  public boolean isVisible(UUID documentId, UUID actor, boolean admin) {
    return documents.findById(documentId).map(d -> canAccess(d, actor, admin)).orElse(false);
  }

  private boolean canAccess(Document document, UUID actor, boolean admin) {
    // Owner and admin short-circuit; reviewer access (direct or via team) is one query (issue
    // #312).
    return admin
        || document.getOwnerId().equals(actor)
        || participants.existsAccessibleParticipant(document.getId(), actor);
  }

  private DocumentVersion requireVersion(UUID documentId, int versionNumber) {
    return versions
        .findByDocumentIdAndVersionNumber(documentId, versionNumber)
        .orElseThrow(
            () -> DocumentValidationException.notFound("no such version: " + versionNumber));
  }

  /** Entity-free document metadata for the web layer. */
  public record DocumentView(
      UUID id,
      String title,
      UUID ownerId,
      String workflowState,
      int latestVersionNumber,
      Instant createdAt,
      Instant updatedAt,
      Instant dueAt) {}

  /** Entity-free version metadata for the web layer. */
  public record DocumentVersionView(
      int versionNumber,
      String contentType,
      long sizeBytes,
      String contentHash,
      String extractionStatus,
      UUID createdBy,
      Instant createdAt) {}

  /**
   * The original binary plus what the controller needs for headers — deliberately plain fields, so
   * the web layer never touches the SPI's {@code StorageContent}. The caller closes the stream (or
   * hands it to an {@code InputStreamResource}, which closes it after writing the response).
   */
  public record OriginalDownload(
      String title,
      int versionNumber,
      String contentHash,
      InputStream stream,
      long contentLength,
      String contentType)
      implements AutoCloseable {

    @Override
    public void close() {
      try {
        stream.close();
      } catch (IOException e) {
        // best-effort close; nothing actionable
      }
    }
  }
}
