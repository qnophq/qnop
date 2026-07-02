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

import io.qnop.api.v1.endpoint.DocumentsApi;
import io.qnop.api.v1.model.DocumentResponse;
import io.qnop.api.v1.model.DocumentVersionListResponse;
import io.qnop.api.v1.model.DocumentVersionSummary;
import io.qnop.api.v1.model.ExtractionStatus;
import io.qnop.api.v1.model.RenderedDocumentResponse;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.document.DocumentAccessService.DocumentVersionView;
import io.qnop.service.document.DocumentAccessService.DocumentView;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document metadata, versions, and the canonical rendered representation (issue #245, ADR-0032),
 * implementing the generated {@link DocumentsApi} contract. Authorization (owner / participant /
 * admin, otherwise 404) lives in {@link DocumentAccessService}; this layer only maps views to the
 * published models. The multipart upload and the original-binary download are plain controllers
 * (ADR-0028): {@code DocumentUploadController} / {@code DocumentContentController}.
 */
@RestController
public class DocumentsController implements DocumentsApi {

  private final DocumentAccessService documents;

  public DocumentsController(DocumentAccessService documents) {
    this.documents = documents;
  }

  @Override
  public ResponseEntity<DocumentResponse> getDocument(UUID documentId) {
    DocumentView view =
        documents.getDocument(documentId, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.ok(
        new DocumentResponse()
            .id(view.id())
            .title(view.title())
            .ownerId(view.ownerId())
            .workflowState(view.workflowState())
            .latestVersionNumber(view.latestVersionNumber())
            .createdAt(view.createdAt().atOffset(ZoneOffset.UTC))
            .updatedAt(view.updatedAt().atOffset(ZoneOffset.UTC)));
  }

  @Override
  public ResponseEntity<DocumentVersionListResponse> listDocumentVersions(UUID documentId) {
    var versions =
        documents.listVersions(documentId, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.ok(
        new DocumentVersionListResponse()
            .versions(versions.stream().map(DocumentsController::toSummary).toList()));
  }

  @Override
  public ResponseEntity<RenderedDocumentResponse> getRenderedDocument(
      UUID documentId, Integer versionNumber) {
    return ResponseEntity.ok(
        documents.getRendered(
            documentId, versionNumber, CurrentUser.requireUserId(), CurrentUser.isAdmin()));
  }

  private static DocumentVersionSummary toSummary(DocumentVersionView view) {
    return new DocumentVersionSummary()
        .versionNumber(view.versionNumber())
        .contentType(view.contentType())
        .sizeBytes(view.sizeBytes())
        .contentHash(view.contentHash())
        .extractionStatus(ExtractionStatus.fromValue(view.extractionStatus()))
        .createdBy(view.createdBy())
        .createdAt(view.createdAt().atOffset(ZoneOffset.UTC));
  }
}
