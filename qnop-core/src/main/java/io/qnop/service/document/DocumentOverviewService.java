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
import io.qnop.repository.DocumentLatestContentType;
import io.qnop.repository.DocumentMaxVersion;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ParticipantProjection;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.UserDisplayName;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserSlug;
import io.qnop.service.document.ReviewModerationFilter.Role;
import io.qnop.service.document.ReviewModerationFilter.Scope;
import io.qnop.service.document.ReviewModerationFilter.State;
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

  /**
   * The reviews the caller may list.
   *
   * <p>{@code moderation} is the admin's cross-review listing (issue #563): every review, not just
   * the caller's own participations. It is refused for anyone else here rather than in the
   * controller, so the rule sits next to the query it guards.
   *
   * <p>Unlike the participant-scoped view, this one is meant to be paged and searched on the server
   * — a moderation list that silently stops at the first page would be worse than no list, because
   * "I can see everything" is exactly what a moderator relies on.
   */
  @Transactional(readOnly = true)
  public DocumentPage listVisible(
      UUID actor,
      boolean admin,
      boolean moderation,
      String query,
      String sort,
      String scope,
      String state,
      String role,
      boolean includeActive,
      boolean includeArchived,
      int page,
      int size) {
    if (moderation && !admin) {
      // 403, not 404: the caller is authenticated and the listing exists — it is
      // simply not theirs. Anti-enumeration does not apply, because this reveals
      // nothing about any particular review.
      throw DocumentValidationException.forbidden("only admins may list every review");
    }
    String like =
        query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    // The retention slice (issue #576): the caller picks any combination — the
    // overview's "All" facet spans active AND archived at once, the default
    // hides the records, the Archived view shows only them. In the participant-
    // scoped view the finer slices stay client-side facets over the result; the
    // moderation listing is paged, so they have to be predicates (issue #563).
    PageRequest pageRequest = PageRequest.of(page, size, parseSort(sort));
    ReviewFacetCounts facets = null;
    Page<Document> result;
    if (moderation) {
      Scope wantedScope = Scope.of(scope);
      State wantedState = State.of(state);
      Role wantedRole = Role.of(role);
      result =
          documents.findAll(
              ReviewModerationFilter.of(actor, like, wantedScope, wantedState, wantedRole),
              pageRequest);
      facets = countFacets(actor, like, wantedScope, wantedState, wantedRole);
    } else {
      result = documents.findVisibleTo(actor, like, includeActive, includeArchived, pageRequest);
    }

    List<UUID> ids = result.getContent().stream().map(Document::getId).toList();
    Map<UUID, Integer> maxVersions =
        ids.isEmpty()
            ? Map.of()
            : versions.findMaxVersionsByDocumentIds(ids).stream()
                .collect(
                    Collectors.toMap(
                        DocumentMaxVersion::documentId, DocumentMaxVersion::maxVersion));
    // Latest-version MIME types for the typed document icon (issue #509).
    Map<UUID, String> contentTypes =
        ids.isEmpty()
            ? Map.of()
            : versions.findLatestContentTypesByDocumentIds(ids).stream()
                .collect(
                    Collectors.toMap(
                        DocumentLatestContentType::documentId,
                        DocumentLatestContentType::contentType));
    // Visibility-scoped (issue #413): under PRIVATE the overview counts follow
    // what the caller can actually see, not the true (possibly larger) set.
    Map<UUID, DocumentAnnotationCounts> counts =
        ids.isEmpty()
            ? Map.of()
            : annotations.countVisibleByDocumentIds(ids, actor, admin).stream()
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

    // Only in moderation mode: everywhere else every row is the caller's by
    // construction, so the extra query would answer a question nobody asked.
    Set<UUID> participating =
        moderation && !ids.isEmpty()
            ? new HashSet<>(documents.findParticipatingIds(ids, actor))
            : Set.copyOf(ids);

    List<DocumentSummaryView> items =
        result.getContent().stream()
            .map(
                document -> {
                  DocumentAnnotationCounts count = counts.get(document.getId());
                  return new DocumentSummaryView(
                      document.getId(),
                      document.getTitle(),
                      document.getSlug(),
                      contentTypes.get(document.getId()),
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
                      document.getDueAt(),
                      document.getArchivedAt(),
                      participating.contains(document.getId()));
                })
            .toList();
    return new DocumentPage(items, result.getTotalElements(), page, size, facets);
  }

  /**
   * The reviewer set for one summary card, anonymised (issue #422) when the review is anonymous and
   * the caller is not its owner — so the overview never leaks the roster of an anonymous review.
   */
  /**
   * The chip counts, each group counted against the other group's selection.
   *
   * <p>Nine counts rather than one grouped query: they are plain indexed counts over a table whose
   * rows are reviews, and keeping each chip's number the result of the very predicate that chip
   * applies is what stops the two drifting apart.
   */
  private ReviewFacetCounts countFacets(
      UUID actor, String like, Scope scope, State state, Role role) {
    return new ReviewFacetCounts(
        // Told apart from "this filter matched nothing", which is a different
        // message and must not show the first-run welcome.
        documents.count(ReviewModerationFilter.of(actor, null, Scope.ALL, State.ANY, Role.ANY)),
        documents.count(ReviewModerationFilter.of(actor, like, scope, state, Role.ANY)),
        documents.count(ReviewModerationFilter.of(actor, like, scope, state, Role.OWNER)),
        documents.count(ReviewModerationFilter.of(actor, like, scope, state, Role.REVIEWER)),
        documents.count(ReviewModerationFilter.of(actor, like, scope, state, Role.OBSERVER)),
        documents.count(ReviewModerationFilter.of(actor, like, Scope.ACTIVE, State.ANY, role)),
        documents.count(ReviewModerationFilter.of(actor, like, Scope.ACTIVE, State.OPEN, role)),
        documents.count(ReviewModerationFilter.of(actor, like, Scope.ACTIVE, State.CLOSED, role)),
        documents.count(ReviewModerationFilter.of(actor, like, Scope.ARCHIVED, State.ANY, role)),
        documents.count(ReviewModerationFilter.of(actor, like, Scope.ALL, State.ANY, role)));
  }

  private List<ParticipantView> rosterFor(
      Document document, UUID actor, List<ParticipantProjection> rows, Map<UUID, String> slugById) {
    if (!document.isAnonymous() || document.getOwnerId().equals(actor)) {
      return rows.stream().map(row -> ParticipantView.of(row, slugById)).toList();
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
      String contentType,
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
      Instant dueAt,
      Instant archivedAt,
      /**
       * Whether the caller owns or reviews this one (issue #563); always true outside moderation.
       */
      boolean participating) {}

  /** A page of the caller's documents; {@code facets} is set only when moderating (issue #563). */
  public record DocumentPage(
      List<DocumentSummaryView> items, long total, int page, int size, ReviewFacetCounts facets) {}

  /**
   * What each chip would show if clicked.
   *
   * <p>Counted on the server for the same reason the rows are paged there: a number derived from
   * one page would describe that page while appearing to describe the workspace. Each group is
   * counted against the OTHER group's current selection, which is the rule the participant-scoped
   * view already follows — so a chip's number keeps predicting what clicking it shows.
   */
  public record ReviewFacetCounts(
      /** Every review there is, ignoring search and facets — "is the workspace empty?" (#563). */
      long totalUnfiltered,
      long roleAny,
      long roleOwner,
      long roleReviewer,
      long roleObserver,
      long stateActive,
      long stateOpen,
      long stateClosed,
      long stateArchived,
      long stateAll) {}
}
