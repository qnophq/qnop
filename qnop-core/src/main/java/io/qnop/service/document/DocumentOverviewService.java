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
import io.qnop.repository.UserDisplayName;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserSlug;
import io.qnop.service.document.ReviewParticipantService.ParticipantView;
import io.qnop.service.review.ReviewIdentityResolver;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
  private final ReviewIdentityResolver identity;
  private final UserRepository users;

  public DocumentOverviewService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      AnnotationRepository annotations,
      ReviewParticipantRepository participants,
      ReviewIdentityResolver identity,
      UserRepository users) {
    this.documents = documents;
    this.versions = versions;
    this.annotations = annotations;
    this.participants = participants;
    this.identity = identity;
    this.users = users;
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
    // Visibility-scoped (issue #413): under PRIVATE the overview counts follow
    // what the caller can actually see, not the true (possibly larger) set.
    Map<UUID, DocumentAnnotationCounts> counts =
        ids.isEmpty()
            ? Map.of()
            : annotations.countVisibleByDocumentIds(ids, actor).stream()
                .collect(
                    Collectors.toMap(DocumentAnnotationCounts::documentId, Function.identity()));
    // Owner names, batched (issue #469 polish): structurally public (#413).
    Set<UUID> ownerIds =
        result.getContent().stream().map(Document::getOwnerId).collect(Collectors.toSet());
    Map<UUID, String> ownerNames =
        ownerIds.isEmpty()
            ? Map.of()
            : users.findDisplayNamesByIdIn(ownerIds).stream()
                .collect(Collectors.toMap(UserDisplayName::id, UserDisplayName::displayName));
    Map<UUID, List<ParticipantProjection>> participantsByDocument =
        ids.isEmpty()
            ? Map.of()
            : participants.findViewsByDocumentIds(ids).stream()
                .collect(Collectors.groupingBy(ParticipantProjection::documentId));
    // Profile slugs for pretty profile links (issue #486), one batch across
    // the owners and every visible (non-anonymised) participant.
    Set<UUID> slugCandidates = new HashSet<>(ownerIds);
    participantsByDocument.values().stream()
        .flatMap(List::stream)
        .map(ParticipantProjection::userId)
        .filter(Objects::nonNull)
        .forEach(slugCandidates::add);
    Map<UUID, String> slugById =
        slugCandidates.isEmpty()
            ? Map.of()
            : users.findSlugsByIdIn(slugCandidates).stream()
                .filter(row -> row.slug() != null)
                .collect(Collectors.toMap(UserSlug::id, UserSlug::slug));

    List<DocumentSummaryView> items =
        result.getContent().stream()
            .map(
                document -> {
                  DocumentAnnotationCounts count = counts.get(document.getId());
                  return new DocumentSummaryView(
                      document.getId(),
                      document.getTitle(),
                      document.getSlug(),
                      document.isAnonymous(),
                      document.getThreadParticipation().name(),
                      document.getOwnerId(),
                      slugById.get(document.getOwnerId()),
                      ownerNames.getOrDefault(document.getOwnerId(), ""),
                      document.getWorkflowState(),
                      maxVersions.getOrDefault(document.getId(), 0),
                      count == null ? 0 : Math.toIntExact(count.total()),
                      count == null ? 0 : Math.toIntExact(count.open()),
                      rosterFor(
                          document,
                          actor,
                          participantsByDocument.getOrDefault(document.getId(), List.of()),
                          slugById),
                      document.getCreatedAt(),
                      document.getUpdatedAt(),
                      document.getDueAt());
                })
            .toList();
    return new DocumentPage(items, result.getTotalElements(), page, size);
  }

  /**
   * The reviewer set for one summary card, anonymised (issue #422) when the review is anonymous and
   * the caller is not its owner — so the overview never leaks the roster of an anonymous review.
   */
  private List<ParticipantView> rosterFor(
      Document document, UUID actor, List<ParticipantProjection> rows, Map<UUID, String> slugById) {
    if (!document.isAnonymous() || document.getOwnerId().equals(actor)) {
      return rows.stream().map(row -> ParticipantView.of(row, slugById.get(row.userId()))).toList();
    }
    return ReviewParticipantService.anonymiseRoster(
        document.getId(), rows, identity.forDocument(document.getId(), actor));
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
      String slug,
      boolean anonymous,
      String threadParticipation,
      UUID ownerId,
      String ownerSlug,
      String ownerDisplayName,
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
