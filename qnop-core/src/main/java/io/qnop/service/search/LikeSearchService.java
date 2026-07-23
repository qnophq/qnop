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
 * LIKE} federation over the very queries the reviews overview and the principal directory already
 * use — {@code DocumentRepository.findVisibleTo} (owner OR participant OR team-participant, title
 * only, no admin bypass) and the enabled-principals searches (names only, never email). The {@code
 * like} pattern is built exactly as those services build it, so a hit here is always a hit there.
 */
@Service
public class LikeSearchService implements SearchService {

  /** Review hits arrive freshest-first — the review you touched yesterday outranks 2024's. */
  private static final Sort REVIEW_SORT = Sort.by(Sort.Direction.DESC, "updatedAt");

  private final DocumentRepository documents;
  private final UserRepository users;
  private final TeamRepository teams;
  private final TeamMembershipRepository memberships;

  public LikeSearchService(
      DocumentRepository documents,
      UserRepository users,
      TeamRepository teams,
      TeamMembershipRepository memberships) {
    this.documents = documents;
    this.users = users;
    this.teams = teams;
    this.memberships = memberships;
  }

  @Override
  @Transactional(readOnly = true)
  public GlobalSearchView quick(UUID actor, boolean admin, String query) {
    String like = likeOf(query);
    if (like == null) {
      return new GlobalSearchView(
          new GroupView<>(List.of(), 0),
          new GroupView<>(List.of(), 0),
          new GroupView<>(List.of(), 0));
    }
    Page<Document> reviewPage =
        documents.findVisibleTo(actor, like, PageRequest.of(0, QUICK_SIZE, REVIEW_SORT));
    Page<User> userPage = users.pageEnabledPrincipals(like, PageRequest.of(0, QUICK_SIZE));
    Page<Team> teamPage = teams.pageEnabledPrincipals(like, PageRequest.of(0, QUICK_SIZE));
    Set<UUID> reachable = reachableTeamIds(actor);
    return new GlobalSearchView(
        new GroupView<>(toReviewHits(reviewPage), reviewPage.getTotalElements()),
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
