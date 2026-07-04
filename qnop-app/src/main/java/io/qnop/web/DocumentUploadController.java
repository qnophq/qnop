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
package io.qnop.web;

import io.qnop.service.document.DocumentIngestService;
import io.qnop.service.document.DocumentIngestService.UploadResult;
import io.qnop.service.document.DocumentValidationException;
import io.qnop.service.document.UploadSource;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Server-mediated document upload (issue #245, ADR-0032 §5): creates a document with its first
 * version, or appends a version to an existing document (owner-only). A plain multipart controller
 * by design (ADR-0028) — binary uploads stay outside the generated OpenAPI contract. Validation
 * (real-PDF sniffing, size cap) and the transactional version+extraction-job write live in {@link
 * DocumentIngestService}; errors surface as the uniform {@code ErrorResponse} envelope via {@code
 * DocumentExceptionHandler}.
 */
@RestController
@RequestMapping("/documents") // mounted under /api/v1 by ApiPathConfig
public class DocumentUploadController {

  private final DocumentIngestService ingest;

  public DocumentUploadController(DocumentIngestService ingest) {
    this.ingest = ingest;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<DocumentUploadResponse> createDocument(
      @RequestParam("title") String title,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "dueAt", required = false) String dueAt) {
    UploadResult result =
        ingest.createDocument(
            CurrentUser.requireUserId(), title, sourceOf(file), parseDueAt(dueAt));
    return ResponseEntity.status(HttpStatus.CREATED).body(DocumentUploadResponse.of(result));
  }

  @PostMapping(value = "/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<DocumentUploadResponse> addVersion(
      @PathVariable UUID documentId, @RequestParam("file") MultipartFile file) {
    UploadResult result =
        ingest.addVersion(CurrentUser.requireUserId(), documentId, sourceOf(file));
    return ResponseEntity.status(HttpStatus.CREATED).body(DocumentUploadResponse.of(result));
  }

  /** Parses the optional ISO-8601 instant form field; blank/absent means no deadline (#295). */
  private static Instant parseDueAt(String dueAt) {
    if (dueAt == null || dueAt.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(dueAt);
    } catch (DateTimeParseException e) {
      throw DocumentValidationException.invalidRequest("dueAt must be an ISO-8601 instant");
    }
  }

  /**
   * Adapts the multipart part to the framework-free {@link UploadSource} the ingest service
   * consumes (issue #361), so the upload streams through without being materialized in the heap and
   * {@code MultipartFile} never crosses into {@code qnop-core} (ADR-0004). {@code getInputStream}
   * may be opened more than once (ingest sniffs, then stages).
   */
  private static UploadSource sourceOf(MultipartFile file) {
    return new UploadSource() {
      @Override
      public InputStream open() throws IOException {
        return file.getInputStream();
      }

      @Override
      public long declaredSize() {
        return file.getSize();
      }
    };
  }

  /** What the SPA needs after an upload: where the version landed and its extraction state. */
  public record DocumentUploadResponse(
      UUID documentId, int versionNumber, String extractionStatus) {

    static DocumentUploadResponse of(UploadResult result) {
      return new DocumentUploadResponse(
          result.documentId(), result.versionNumber(), result.extractionStatus());
    }
  }
}
