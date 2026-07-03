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
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.DocumentAnnotationCounts;
import io.qnop.repository.DocumentMaxVersion;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ParticipantProjection;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.service.document.ReviewParticipantService.ParticipantView;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reviews overview (issue #292): every document the caller owns or participates in — directly
 * or through a team — with review progress and the reviewer set. Deliberately personal: admins get
 * their own list here too and use the direct document endpoints for oversight. Counts and
 * participants are batched per page to avoid N+1.
 */
@Service
public class DocumentOverviewService {

  private static final Map<String, String> SORT_FIELDS =
      Map.of("updatedAt", "updatedAt", "createdAt", "createdAt", "title", "title");

  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final AnnotationRepository annotations;
  private final ReviewParticipantRepository participants;

  public DocumentOverviewService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      AnnotationRepository annotations,
      ReviewParticipantRepository participants) {
    this.documents = documents;
    this.versions = versions;
    this.annotations = annotations;
    this.participants = participants;
  }

  @Transactional(readOnly = true)
  public DocumentPage listVisible(UUID actor, String query, String sort, int page, int size) {
    String like =
        query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    Page<Document> result =
        documents.findVisibleTo(actor, like, PageRequest.of(page, size, parseSort(sort)));

    List<UUID> ids = result.getContent().stream().map(Document::getId).toList();
    Map<UUID, Integer> maxVersions =
        ids.isEmpty()
            ? Map.of()
            : versions.findMaxVersionsByDocumentIds(ids).stream()
                .collect(
                    Collectors.toMap(
                        DocumentMaxVersion::documentId, DocumentMaxVersion::maxVersion));
    Map<UUID, DocumentAnnotationCounts> counts =
        ids.isEmpty()
            ? Map.of()
            : annotations.countByDocumentIds(ids).stream()
                .collect(
                    Collectors.toMap(DocumentAnnotationCounts::documentId, Function.identity()));
    Map<UUID, List<ParticipantProjection>> participantsByDocument =
        ids.isEmpty()
            ? Map.of()
            : participants.findViewsByDocumentIds(ids).stream()
                .collect(Collectors.groupingBy(ParticipantProjection::documentId));

    List<DocumentSummaryView> items =
        result.getContent().stream()
            .map(
                document -> {
                  DocumentAnnotationCounts count = counts.get(document.getId());
                  return new DocumentSummaryView(
                      document.getId(),
                      document.getTitle(),
                      document.getOwnerId(),
                      document.getWorkflowState(),
                      maxVersions.getOrDefault(document.getId(), 0),
                      count == null ? 0 : Math.toIntExact(count.total()),
                      count == null ? 0 : Math.toIntExact(count.open()),
                      participantsByDocument.getOrDefault(document.getId(), List.of()).stream()
                          .map(ParticipantView::of)
                          .toList(),
                      document.getCreatedAt(),
                      document.getUpdatedAt(),
                      document.getDueAt());
                })
            .toList();
    return new DocumentPage(items, result.getTotalElements(), page, size);
  }

  private Sort parseSort(String sort) {
    String field = "updatedAt";
    Sort.Direction direction = Sort.Direction.DESC;
    if (sort != null && !sort.isBlank()) {
      String[] parts = sort.split(",", 2);
      String candidate = SORT_FIELDS.get(parts[0].trim());
      if (candidate != null) {
        field = candidate;
        direction =
            parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
      }
    }
    return Sort.by(direction, field);
  }

  /** One document in the caller's overview. */
  public record DocumentSummaryView(
      UUID id,
      String title,
      UUID ownerId,
      String workflowState,
      int latestVersionNumber,
      int annotationCount,
      int openAnnotationCount,
      List<ParticipantView> participants,
      Instant createdAt,
      Instant updatedAt,
      Instant dueAt) {}

  /** A page of the caller's documents. */
  public record DocumentPage(List<DocumentSummaryView> items, long total, int page, int size) {}
}
