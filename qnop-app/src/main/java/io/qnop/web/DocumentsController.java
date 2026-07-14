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
import io.qnop.api.v1.model.DiffChange;
import io.qnop.api.v1.model.DiffChangeType;
import io.qnop.api.v1.model.DiffLocation;
import io.qnop.api.v1.model.DocumentListResponse;
import io.qnop.api.v1.model.DocumentResponse;
import io.qnop.api.v1.model.DocumentSummary;
import io.qnop.api.v1.model.DocumentUpdateRequest;
import io.qnop.api.v1.model.DocumentVersionListResponse;
import io.qnop.api.v1.model.DocumentVersionSummary;
import io.qnop.api.v1.model.ExtractionStatus;
import io.qnop.api.v1.model.NormalizedBox;
import io.qnop.api.v1.model.ParticipantCreateRequest;
import io.qnop.api.v1.model.ParticipantKind;
import io.qnop.api.v1.model.ParticipantListResponse;
import io.qnop.api.v1.model.ParticipantView;
import io.qnop.api.v1.model.RenderedDocumentResponse;
import io.qnop.api.v1.model.ThreadParticipation;
import io.qnop.api.v1.model.VersionDiffResponse;
import io.qnop.api.v1.model.VisitResponse;
import io.qnop.service.diff.VersionDiffService;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.document.DocumentAccessService.DocumentVersionView;
import io.qnop.service.document.DocumentAccessService.DocumentView;
import io.qnop.service.document.DocumentOverviewService;
import io.qnop.service.document.DocumentUpdateService;
import io.qnop.service.document.ReviewParticipantService;
import io.qnop.service.review.ReviewVisitService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document metadata, versions, the canonical rendered representation (issue #245, ADR-0032), and
 * the inter-version diff (issue #249, ADR-0034), implementing the generated {@link DocumentsApi}
 * contract. Authorization (owner / participant / admin, otherwise 404) lives in the services; this
 * layer only maps views to the published models. The multipart upload and the original-binary
 * download are plain controllers (ADR-0028): {@code DocumentUploadController} / {@code
 * DocumentContentController}.
 */
@RestController
public class DocumentsController implements DocumentsApi {

  private final DocumentAccessService documents;
  private final VersionDiffService diffs;
  private final DocumentOverviewService overview;
  private final ReviewParticipantService participants;
  private final DocumentUpdateService updates;
  private final ReviewVisitService visits;

  public DocumentsController(
      DocumentAccessService documents,
      VersionDiffService diffs,
      DocumentOverviewService overview,
      ReviewParticipantService participants,
      DocumentUpdateService updates,
      ReviewVisitService visits) {
    this.documents = documents;
    this.diffs = diffs;
    this.overview = overview;
    this.participants = participants;
    this.updates = updates;
    this.visits = visits;
  }

  @Override
  public ResponseEntity<DocumentListResponse> listDocuments(
      String q, String sort, Integer page, Integer size) {
    DocumentOverviewService.DocumentPage result =
        overview.listVisible(
            CurrentUser.requireUserId(),
            q,
            sort,
            page == null ? 0 : page,
            size == null ? 20 : size);
    return ResponseEntity.ok(
        new DocumentListResponse()
            .items(result.items().stream().map(DocumentsController::toSummary).toList())
            .total(result.total())
            .page(result.page())
            .size(result.size()));
  }

  @Override
  public ResponseEntity<ParticipantListResponse> listParticipants(UUID documentId) {
    return ResponseEntity.ok(
        new ParticipantListResponse()
            .participants(
                participants
                    .list(documentId, CurrentUser.requireUserId(), CurrentUser.isAdmin())
                    .stream()
                    .map(DocumentsController::toParticipant)
                    .toList()));
  }

  @Override
  public ResponseEntity<ParticipantView> addParticipant(
      UUID documentId, ParticipantCreateRequest request) {
    ReviewParticipantService.ParticipantView added =
        participants.add(
            documentId,
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin(),
            request.getUserId(),
            request.getTeamId());
    return ResponseEntity.status(201).body(toParticipant(added));
  }

  @Override
  public ResponseEntity<Void> removeParticipant(UUID documentId, UUID participantId) {
    participants.remove(
        documentId, participantId, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.noContent().build();
  }

  private static DocumentSummary toSummary(DocumentOverviewService.DocumentSummaryView view) {
    return new DocumentSummary()
        .id(view.id())
        .title(view.title())
        .slug(view.slug())
        .anonymous(view.anonymous())
        .threadParticipation(ThreadParticipation.fromValue(view.threadParticipation()))
        .ownerId(view.ownerId())
        .ownerSlug(view.ownerSlug())
        .ownerDisplayName(view.ownerDisplayName())
        .workflowState(view.workflowState())
        .latestVersionNumber(view.latestVersionNumber())
        .annotationCount(view.annotationCount())
        .openAnnotationCount(view.openAnnotationCount())
        .participants(view.participants().stream().map(DocumentsController::toParticipant).toList())
        .createdAt(view.createdAt().atOffset(ZoneOffset.UTC))
        .updatedAt(view.updatedAt().atOffset(ZoneOffset.UTC))
        .dueAt(atUtc(view.dueAt()));
  }

  /** Nullable {@link Instant} → {@link OffsetDateTime} at UTC for the wire model (#295). */
  private static OffsetDateTime atUtc(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  /** Nullable wire {@link OffsetDateTime} → domain {@link Instant} (#295). */
  private static Instant toInstant(OffsetDateTime dateTime) {
    return dateTime == null ? null : dateTime.toInstant();
  }

  private static ParticipantView toParticipant(ReviewParticipantService.ParticipantView view) {
    return new ParticipantView()
        .id(view.id())
        .kind(view.team() ? ParticipantKind.TEAM : ParticipantKind.USER)
        .principalId(view.principalId())
        .slug(view.slug())
        .displayName(view.displayName());
  }

  @Override
  public ResponseEntity<VersionDiffResponse> getVersionDiff(
      UUID documentId, Integer from, Integer to) {
    VersionDiffService.DiffView view =
        diffs.diff(documentId, from, to, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.ok(
        new VersionDiffResponse()
            .fromVersion(view.fromVersion())
            .toVersion(view.toVersion())
            .changes(view.changes().stream().map(DocumentsController::toDto).toList()));
  }

  private static DiffChange toDto(VersionDiffService.ChangeView change) {
    return new DiffChange()
        .type(DiffChangeType.fromValue(change.type()))
        .fromText(change.fromText())
        .fromLocations(change.fromLocations().stream().map(DocumentsController::toDto).toList())
        .toText(change.toText())
        .toLocations(change.toLocations().stream().map(DocumentsController::toDto).toList());
  }

  private static DiffLocation toDto(VersionDiffService.LocationView location) {
    return new DiffLocation()
        .surfaceIndex(location.surfaceIndex())
        .box(
            new NormalizedBox()
                .x(location.x())
                .y(location.y())
                .width(location.width())
                .height(location.height()));
  }

  @Override
  public ResponseEntity<DocumentResponse> getDocument(UUID documentId) {
    DocumentView view =
        documents.getDocument(documentId, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.ok(toResponse(view));
  }

  @Override
  public ResponseEntity<DocumentResponse> getDocumentBySlug(String slug) {
    DocumentView view =
        documents.getDocumentBySlug(slug, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.ok(toResponse(view));
  }

  @Override
  public ResponseEntity<VisitResponse> recordVisit(UUID documentId) {
    Instant previous =
        visits.recordVisit(documentId, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.ok(
        new VisitResponse()
            .previousSeenAt(previous == null ? null : previous.atOffset(ZoneOffset.UTC)));
  }

  @Override
  public ResponseEntity<DocumentResponse> updateDocument(
      UUID documentId, DocumentUpdateRequest request) {
    DocumentView view =
        updates.updateDueDate(
            documentId,
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin(),
            toInstant(request.getDueAt()));
    return ResponseEntity.ok(toResponse(view));
  }

  private static DocumentResponse toResponse(DocumentView view) {
    return new DocumentResponse()
        .id(view.id())
        .title(view.title())
        .slug(view.slug())
        .anonymous(view.anonymous())
        .threadParticipation(ThreadParticipation.fromValue(view.threadParticipation()))
        .ownerId(view.ownerId())
        .ownerSlug(view.ownerSlug())
        .workflowState(view.workflowState())
        .latestVersionNumber(view.latestVersionNumber())
        .createdAt(view.createdAt().atOffset(ZoneOffset.UTC))
        .updatedAt(view.updatedAt().atOffset(ZoneOffset.UTC))
        .dueAt(atUtc(view.dueAt()));
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
