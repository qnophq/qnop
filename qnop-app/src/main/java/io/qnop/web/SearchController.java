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

import io.qnop.api.v1.endpoint.SearchApi;
import io.qnop.api.v1.model.AnnotationStatus;
import io.qnop.api.v1.model.DiscussionSearchGroup;
import io.qnop.api.v1.model.DiscussionSearchHit;
import io.qnop.api.v1.model.DiscussionSearchPage;
import io.qnop.api.v1.model.GlobalSearchResponse;
import io.qnop.api.v1.model.ReviewSearchGroup;
import io.qnop.api.v1.model.ReviewSearchHit;
import io.qnop.api.v1.model.ReviewSearchPage;
import io.qnop.api.v1.model.TeamSearchGroup;
import io.qnop.api.v1.model.TeamSearchHit;
import io.qnop.api.v1.model.TeamSearchPage;
import io.qnop.api.v1.model.UserSearchGroup;
import io.qnop.api.v1.model.UserSearchHit;
import io.qnop.api.v1.model.UserSearchPage;
import io.qnop.service.avatar.AvatarService;
import io.qnop.service.search.SearchService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The federated global search (issue #540, ADR-0047), implementing the generated {@link SearchApi}
 * contract. Scoping lives in the {@link SearchService} port; this layer maps view records to the
 * published models and adds the users' avatar URLs (one batched timestamp lookup per response — the
 * {@code PrincipalsController} pattern).
 */
@RestController
public class SearchController implements SearchApi {

  private final SearchService search;
  private final AvatarService avatars;

  public SearchController(SearchService search, AvatarService avatars) {
    this.search = search;
    this.avatars = avatars;
  }

  @Override
  public ResponseEntity<GlobalSearchResponse> searchQuick(String q) {
    SearchService.GlobalSearchView view =
        search.quick(CurrentUser.requireUserId(), CurrentUser.isAdmin(), q);
    Map<UUID, Instant> avatarTimestamps = avatarTimestamps(view.users().items());
    return ResponseEntity.ok(
        new GlobalSearchResponse()
            .reviews(
                new ReviewSearchGroup()
                    .items(view.reviews().items().stream().map(SearchController::toReview).toList())
                    .total(view.reviews().total()))
            .annotations(toDiscussionGroup(view.annotations()))
            .comments(toDiscussionGroup(view.comments()))
            .users(
                new UserSearchGroup()
                    .items(
                        view.users().items().stream()
                            .map(hit -> toUser(hit, avatarTimestamps))
                            .toList())
                    .total(view.users().total()))
            .teams(
                new TeamSearchGroup()
                    .items(view.teams().items().stream().map(SearchController::toTeam).toList())
                    .total(view.teams().total())));
  }

  @Override
  public ResponseEntity<ReviewSearchPage> searchReviews(String q, Integer page, Integer size) {
    SearchService.PageView<SearchService.ReviewHitView> result =
        search.reviews(CurrentUser.requireUserId(), q, pageOf(page), sizeOf(size));
    return ResponseEntity.ok(
        new ReviewSearchPage()
            .items(result.items().stream().map(SearchController::toReview).toList())
            .total(result.total())
            .page(result.page())
            .size(result.size()));
  }

  @Override
  public ResponseEntity<DiscussionSearchPage> searchAnnotations(
      String q, Integer page, Integer size) {
    return ResponseEntity.ok(
        toDiscussionPage(
            search.annotations(
                CurrentUser.requireUserId(),
                CurrentUser.isAdmin(),
                q,
                pageOf(page),
                sizeOf(size))));
  }

  @Override
  public ResponseEntity<DiscussionSearchPage> searchComments(String q, Integer page, Integer size) {
    return ResponseEntity.ok(
        toDiscussionPage(
            search.comments(
                CurrentUser.requireUserId(),
                CurrentUser.isAdmin(),
                q,
                pageOf(page),
                sizeOf(size))));
  }

  @Override
  public ResponseEntity<UserSearchPage> searchUsers(String q, Integer page, Integer size) {
    CurrentUser.requireUserId();
    SearchService.PageView<SearchService.UserHitView> result =
        search.users(q, pageOf(page), sizeOf(size));
    Map<UUID, Instant> avatarTimestamps = avatarTimestamps(result.items());
    return ResponseEntity.ok(
        new UserSearchPage()
            .items(result.items().stream().map(hit -> toUser(hit, avatarTimestamps)).toList())
            .total(result.total())
            .page(result.page())
            .size(result.size()));
  }

  @Override
  public ResponseEntity<TeamSearchPage> searchTeams(String q, Integer page, Integer size) {
    SearchService.PageView<SearchService.TeamHitView> result =
        search.teams(
            CurrentUser.requireUserId(), CurrentUser.isAdmin(), q, pageOf(page), sizeOf(size));
    return ResponseEntity.ok(
        new TeamSearchPage()
            .items(result.items().stream().map(SearchController::toTeam).toList())
            .total(result.total())
            .page(result.page())
            .size(result.size()));
  }

  private static int pageOf(Integer page) {
    return page == null ? 0 : page;
  }

  private static int sizeOf(Integer size) {
    return size == null ? 20 : size;
  }

  private Map<UUID, Instant> avatarTimestamps(List<SearchService.UserHitView> hits) {
    return avatars.updatedAt(hits.stream().map(SearchService.UserHitView::id).toList());
  }

  private static ReviewSearchHit toReview(SearchService.ReviewHitView hit) {
    return new ReviewSearchHit()
        .id(hit.id())
        .slug(hit.slug())
        .title(hit.title())
        .workflowState(hit.workflowState());
  }

  private static DiscussionSearchGroup toDiscussionGroup(
      SearchService.GroupView<SearchService.DiscussionHitView> group) {
    return new DiscussionSearchGroup()
        .items(group.items().stream().map(SearchController::toDiscussion).toList())
        .total(group.total());
  }

  private static DiscussionSearchPage toDiscussionPage(
      SearchService.PageView<SearchService.DiscussionHitView> page) {
    return new DiscussionSearchPage()
        .items(page.items().stream().map(SearchController::toDiscussion).toList())
        .total(page.total())
        .page(page.page())
        .size(page.size());
  }

  private static DiscussionSearchHit toDiscussion(SearchService.DiscussionHitView hit) {
    return new DiscussionSearchHit()
        .commentId(hit.commentId())
        .annotationId(hit.annotationId())
        .documentId(hit.documentId())
        .documentSlug(hit.documentSlug())
        .documentTitle(hit.documentTitle())
        .annotationStatus(AnnotationStatus.fromValue(hit.annotationStatus()))
        .excerpt(hit.excerpt());
  }

  private static UserSearchHit toUser(
      SearchService.UserHitView hit, Map<UUID, Instant> avatarTimestamps) {
    return new UserSearchHit()
        .userId(hit.id())
        .displayName(hit.displayName())
        .slug(hit.slug())
        .avatarUrl(AvatarUrls.forUser(hit.id(), avatarTimestamps.get(hit.id())));
  }

  private static TeamSearchHit toTeam(SearchService.TeamHitView hit) {
    return new TeamSearchHit()
        .teamId(hit.id())
        .name(hit.name())
        .slug(hit.slug())
        .viewable(hit.viewable());
  }
}
