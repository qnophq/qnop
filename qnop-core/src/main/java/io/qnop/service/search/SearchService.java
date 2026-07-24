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

import java.util.List;
import java.util.UUID;

/**
 * The federated global-search port (issue #540, ADR-0047): each entity type is answered through its
 * own already-authorization-scoped query and the results are merged, so scoping stays correct by
 * construction — there is no second, weaker access path. The Community default ({@link
 * LikeSearchService}) federates the existing Postgres {@code LIKE} queries; a later Postgres-FTS or
 * OpenSearch implementation replaces the adapter, not the callers (the ADR-0013 stance).
 *
 * <p>Scoping per type, non-negotiable: reviews through the caller's visibility rule, matching the
 * title or the discussion — the latter only in threads the caller may see (ADR-0038: a PRIVATE
 * review hides foreign threads), and never by author name; users and teams through the
 * enabled-principals rule (display name/username and team name — never email, which stays an admin
 * capability). Admins search their own reviews like anyone else (mirroring the reviews overview);
 * their reach shows in team hits being universally {@code viewable} and in the thread-visibility
 * predicate.
 */
public interface SearchService {

  /** Per-group cap of the quick (dropdown) variant. */
  int QUICK_SIZE = 5;

  /** Queries shorter than this (trimmed) yield empty results — never an error. */
  int MIN_QUERY_LENGTH = 2;

  /** The grouped top hits for the dropdown: each group capped, with the full match count. */
  GlobalSearchView quick(UUID actor, boolean admin, String query);

  /** One page of review hits (title matches) visible to {@code actor}. */
  PageView<ReviewHitView> reviews(UUID actor, String query, int page, int size);

  /** One page of annotation hits: matches in thread OPENERS the caller may see. */
  PageView<DiscussionHitView> annotations(
      UUID actor, boolean admin, String query, int page, int size);

  /** One page of comment hits: matches in thread REPLIES the caller may see. */
  PageView<DiscussionHitView> comments(UUID actor, boolean admin, String query, int page, int size);

  /** One page of enabled users matched by display name/username. */
  PageView<UserHitView> users(String query, int page, int size);

  /** One page of enabled teams matched by name, each flagged with the caller's reach. */
  PageView<TeamHitView> teams(UUID actor, boolean admin, String query, int page, int size);

  /** The five quick groups. */
  record GlobalSearchView(
      GroupView<ReviewHitView> reviews,
      GroupView<DiscussionHitView> annotations,
      GroupView<DiscussionHitView> comments,
      GroupView<UserHitView> users,
      GroupView<TeamHitView> teams) {}

  /** A capped group with the group's full match count. */
  record GroupView<T>(List<T> items, long total) {}

  /** One page of hits with the standard envelope facts. */
  record PageView<T>(List<T> items, long total, int page, int size) {}

  /** A review hit — a title match, a strict subset of the reviews-list row (ADR-0038). */
  record ReviewHitView(UUID id, String slug, String title, String workflowState) {}

  /**
   * A discussion hit (ADR-0038-safe): an annotation opener or a reply from a thread the caller may
   * open, with its review context, the annotation's status for the cue, and a windowed excerpt.
   * Never an author name.
   */
  record DiscussionHitView(
      UUID commentId,
      UUID annotationId,
      UUID documentId,
      String documentSlug,
      String documentTitle,
      String annotationStatus,
      String excerpt) {}

  /** A person hit — names only; the web layer adds the avatar URL. */
  record UserHitView(UUID id, String displayName, String slug) {}

  /** A team hit; {@code viewable} = the caller may open the team's roster (member or admin). */
  record TeamHitView(UUID id, String name, String slug, boolean viewable) {}
}
