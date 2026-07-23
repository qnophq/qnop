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
package io.qnop.service.search;

import io.qnop.entity.Document;
import io.qnop.entity.Team;
import io.qnop.entity.User;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DiscussionMatchProjection;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserTeamProjection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Community {@link SearchService} adapter (issue #540, ADR-0047): a Postgres {@code LOWER …
 * LIKE} federation over the very scoping rules the reviews overview and the principal directory
 * already use — review visibility (owner OR participant OR team-participant, no admin bypass) with
 * the ADR-0038 thread-visibility predicate on discussion matches, and the enabled-principals
 * searches (names only, never email). The {@code like} pattern is built exactly as those services
 * build it, so a hit here is always a hit there.
 */
@Service
public class LikeSearchService implements SearchService {

  /** Review hits arrive freshest-first — the review you touched yesterday outranks 2024's. */
  private static final Sort REVIEW_SORT = Sort.by(Sort.Direction.DESC, "updatedAt");

  /** A discussion excerpt shows at most this many characters around the match. */
  static final int EXCERPT_MAX = 120;

  /** Characters of context kept before the match inside the excerpt window. */
  private static final int EXCERPT_LEAD = 32;

  private final DocumentRepository documents;
  private final UserRepository users;
  private final TeamRepository teams;
  private final TeamMembershipRepository memberships;
  private final CommentRepository comments;

  public LikeSearchService(
      DocumentRepository documents,
      UserRepository users,
      TeamRepository teams,
      TeamMembershipRepository memberships,
      CommentRepository comments) {
    this.documents = documents;
    this.users = users;
    this.teams = teams;
    this.memberships = memberships;
    this.comments = comments;
  }

  @Override
  @Transactional(readOnly = true)
  public GlobalSearchView quick(UUID actor, boolean admin, String query) {
    String like = likeOf(query);
    if (like == null) {
      return new GlobalSearchView(
          new GroupView<>(List.of(), 0),
          new GroupView<>(List.of(), 0),
          new GroupView<>(List.of(), 0),
          new GroupView<>(List.of(), 0),
          new GroupView<>(List.of(), 0));
    }
    String term = termOf(like);
    Page<Document> reviewPage =
        documents.findVisibleTo(actor, like, PageRequest.of(0, QUICK_SIZE, REVIEW_SORT));
    Page<DiscussionMatchProjection> openerPage =
        comments.searchAnnotationOpeners(like, actor, admin, PageRequest.of(0, QUICK_SIZE));
    Page<DiscussionMatchProjection> replyPage =
        comments.searchCommentReplies(like, actor, admin, PageRequest.of(0, QUICK_SIZE));
    Page<User> userPage = users.pageEnabledPrincipals(like, PageRequest.of(0, QUICK_SIZE));
    Page<Team> teamPage = teams.pageEnabledPrincipals(like, PageRequest.of(0, QUICK_SIZE));
    Set<UUID> reachable = reachableTeamIds(actor);
    return new GlobalSearchView(
        new GroupView<>(toReviewHits(reviewPage), reviewPage.getTotalElements()),
        new GroupView<>(toDiscussionHits(openerPage, term), openerPage.getTotalElements()),
        new GroupView<>(toDiscussionHits(replyPage, term), replyPage.getTotalElements()),
        new GroupView<>(toUserHits(userPage), userPage.getTotalElements()),
        new GroupView<>(toTeamHits(teamPage, reachable, admin), teamPage.getTotalElements()));
  }

  @Override
  @Transactional(readOnly = true)
  public PageView<ReviewHitView> reviews(UUID actor, String query, int page, int size) {
    String like = likeOf(query);
    if (like == null) {
      return new PageView<>(List.of(), 0, page, size);
    }
    Page<Document> result =
        documents.findVisibleTo(actor, like, PageRequest.of(page, size, REVIEW_SORT));
    return new PageView<>(toReviewHits(result), result.getTotalElements(), page, size);
  }

  @Override
  @Transactional(readOnly = true)
  public PageView<DiscussionHitView> annotations(
      UUID actor, boolean admin, String query, int page, int size) {
    String like = likeOf(query);
    if (like == null) {
      return new PageView<>(List.of(), 0, page, size);
    }
    Page<DiscussionMatchProjection> result =
        comments.searchAnnotationOpeners(like, actor, admin, PageRequest.of(page, size));
    return new PageView<>(
        toDiscussionHits(result, termOf(like)), result.getTotalElements(), page, size);
  }

  @Override
  @Transactional(readOnly = true)
  public PageView<DiscussionHitView> comments(
      UUID actor, boolean admin, String query, int page, int size) {
    String like = likeOf(query);
    if (like == null) {
      return new PageView<>(List.of(), 0, page, size);
    }
    Page<DiscussionMatchProjection> result =
        comments.searchCommentReplies(like, actor, admin, PageRequest.of(page, size));
    return new PageView<>(
        toDiscussionHits(result, termOf(like)), result.getTotalElements(), page, size);
  }

  @Override
  @Transactional(readOnly = true)
  public PageView<UserHitView> users(String query, int page, int size) {
    String like = likeOf(query);
    if (like == null) {
      return new PageView<>(List.of(), 0, page, size);
    }
    Page<User> result = users.pageEnabledPrincipals(like, PageRequest.of(page, size));
    return new PageView<>(toUserHits(result), result.getTotalElements(), page, size);
  }

  @Override
  @Transactional(readOnly = true)
  public PageView<TeamHitView> teams(UUID actor, boolean admin, String query, int page, int size) {
    String like = likeOf(query);
    if (like == null) {
      return new PageView<>(List.of(), 0, page, size);
    }
    Page<Team> result = teams.pageEnabledPrincipals(like, PageRequest.of(page, size));
    return new PageView<>(
        toTeamHits(result, reachableTeamIds(actor), admin), result.getTotalElements(), page, size);
  }

  /**
   * The {@code LIKE} pattern, built exactly like the overview/directory services build theirs — or
   * {@code null} for a query below {@link #MIN_QUERY_LENGTH}: a global box firing per keystroke
   * must never dump the whole workspace, so short queries answer empty instead of unfiltered.
   */
  private static String likeOf(String query) {
    String trimmed = query == null ? "" : query.trim();
    if (trimmed.length() < MIN_QUERY_LENGTH) {
      return null;
    }
    return "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
  }

  private Set<UUID> reachableTeamIds(UUID actor) {
    return memberships.findTeamsOfUser(actor).stream()
        .map(UserTeamProjection::teamId)
        .collect(Collectors.toSet());
  }

  private static List<ReviewHitView> toReviewHits(Page<Document> page) {
    return page.getContent().stream()
        .map(d -> new ReviewHitView(d.getId(), d.getSlug(), d.getTitle(), d.getWorkflowState()))
        .toList();
  }

  private static List<DiscussionHitView> toDiscussionHits(
      Page<DiscussionMatchProjection> page, String term) {
    return page.getContent().stream()
        .map(
            match ->
                new DiscussionHitView(
                    match.commentId(),
                    match.annotationId(),
                    match.documentId(),
                    match.documentSlug(),
                    match.documentTitle(),
                    match.annotationStatus().name(),
                    excerptOf(match.body(), term)))
        .toList();
  }

  /** The raw lowercased search term inside a {@code %term%} pattern. */
  private static String termOf(String like) {
    return like.substring(1, like.length() - 1);
  }

  /**
   * A single-line window of at most {@link #EXCERPT_MAX} characters around the first occurrence of
   * {@code term} (case-insensitive), whitespace flattened, clipped ends marked with an ellipsis.
   */
  static String excerptOf(String body, String term) {
    String flat = body.replaceAll("\\s+", " ").trim();
    int index = flat.toLowerCase(Locale.ROOT).indexOf(term);
    if (index < 0) {
      index = 0; // defensive: the query matched, so this should not happen
    }
    int start = Math.max(0, index - EXCERPT_LEAD);
    int end = Math.min(flat.length(), start + EXCERPT_MAX);
    String window = flat.substring(start, end);
    return (start > 0 ? "…" : "") + window + (end < flat.length() ? "…" : "");
  }

  private static List<UserHitView> toUserHits(Page<User> page) {
    return page.getContent().stream()
        .map(u -> new UserHitView(u.getId(), u.getDisplayName(), u.getSlug()))
        .toList();
  }

  private static List<TeamHitView> toTeamHits(Page<Team> page, Set<UUID> reachable, boolean admin) {
    return page.getContent().stream()
        .map(
            t ->
                new TeamHitView(
                    t.getId(), t.getName(), t.getSlug(), admin || reachable.contains(t.getId())))
        .toList();
  }
}
