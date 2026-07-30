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
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.TeamMembership;
import io.qnop.entity.WorkflowState;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * The facets of the admin's moderation listing, as a query (issue #563).
 *
 * <p>The participant-scoped overview fetches once and facets in the browser, which is honest while
 * the rows are the caller's own. The moderation listing spans a whole workspace and is paged, so
 * the same chips have to filter — and count — on the server. A chip that narrowed the current page
 * while claiming to describe the workspace would be worse than no chip at all.
 *
 * <p>Built with the Criteria API rather than one JPQL query with a fistful of boolean parameters:
 * the facets are independent dimensions, and a predicate assembled from what was actually asked for
 * stays readable as they grow.
 */
final class ReviewModerationFilter {

  /** The retention slice, matching the API's {@code scope}. */
  enum Scope {
    ACTIVE,
    ARCHIVED,
    ALL;

    static Scope of(String raw) {
      return switch (raw == null ? "" : raw.toLowerCase(Locale.ROOT)) {
        case "archived" -> ARCHIVED;
        case "all" -> ALL;
        default -> ACTIVE;
      };
    }
  }

  /** The workflow slice, orthogonal to the retention one. */
  enum State {
    ANY,
    OPEN,
    CLOSED;

    static State of(String raw) {
      return switch (raw == null ? "" : raw.toLowerCase(Locale.ROOT)) {
        case "open" -> OPEN;
        case "closed" -> CLOSED;
        default -> ANY;
      };
    }
  }

  /** The caller's part in the review. */
  enum Role {
    ANY,
    OWNER,
    REVIEWER,
    OBSERVER;

    static Role of(String raw) {
      return switch (raw == null ? "" : raw.toLowerCase(Locale.ROOT)) {
        case "owner" -> OWNER;
        case "reviewer" -> REVIEWER;
        case "observer" -> OBSERVER;
        default -> ANY;
      };
    }
  }

  private ReviewModerationFilter() {}

  /**
   * The closed states, mirrored from {@link WorkflowState#isTerminal()}.
   *
   * <p>Names, not enum constants: the column is a plain string so an enterprise edition can extend
   * the state machine (ADR-0011/0035). That also fixes what "open" means here — everything that is
   * not one of these, so an unknown enterprise state counts as open, the same reading the client
   * uses.
   */
  private static final List<String> CLOSED =
      List.of(WorkflowState.FINALIZED.name(), WorkflowState.CANCELLED.name());

  static Specification<Document> of(UUID actor, String like, Scope scope, State state, Role role) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (like != null) {
        predicates.add(cb.like(cb.lower(root.get("title")), like));
      }
      switch (scope) {
        case ACTIVE -> predicates.add(cb.isNull(root.get("archivedAt")));
        case ARCHIVED -> predicates.add(cb.isNotNull(root.get("archivedAt")));
        case ALL -> {
          // Everything at once, records included.
        }
      }
      switch (state) {
        case OPEN -> predicates.add(cb.not(root.get("workflowState").in(CLOSED)));
        case CLOSED -> predicates.add(root.get("workflowState").in(CLOSED));

        case ANY -> {
          // Every workflow state.
        }
      }
      switch (role) {
        case OWNER -> predicates.add(cb.equal(root.get("ownerId"), actor));
        case REVIEWER ->
            predicates.add(
                cb.and(cb.notEqual(root.get("ownerId"), actor), reviewing(root, query, cb, actor)));
        // "Not participating" is the moderation listing's own facet: neither owner
        // nor on the roster, directly or through a team.
        case OBSERVER ->
            predicates.add(
                cb.and(
                    cb.notEqual(root.get("ownerId"), actor),
                    cb.not(reviewing(root, query, cb, actor))));
        case ANY -> {
          // Whatever the caller's part is.
        }
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  /** Whether the actor is on the roster, directly or through one of their teams. */
  private static Predicate reviewing(
      Root<Document> root,
      jakarta.persistence.criteria.CommonAbstractCriteria query,
      jakarta.persistence.criteria.CriteriaBuilder cb,
      UUID actor) {
    Subquery<UUID> direct = query.subquery(UUID.class);
    Root<ReviewParticipant> participant = direct.from(ReviewParticipant.class);
    direct
        .select(participant.get("id"))
        .where(
            cb.equal(participant.get("documentId"), root.get("id")),
            cb.equal(participant.get("userId"), actor));

    Subquery<UUID> viaTeam = query.subquery(UUID.class);
    Root<ReviewParticipant> teamRow = viaTeam.from(ReviewParticipant.class);
    Root<TeamMembership> membership = viaTeam.from(TeamMembership.class);
    viaTeam
        .select(teamRow.get("id"))
        .where(
            cb.equal(teamRow.get("documentId"), root.get("id")),
            cb.equal(teamRow.get("teamId"), membership.get("teamId")),
            cb.equal(membership.get("userId"), actor));

    return cb.or(cb.exists(direct), cb.exists(viaTeam));
  }
}
