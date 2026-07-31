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
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.TeamMembership;
import io.qnop.entity.WorkflowState;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

  /**
   * The workflow slice, orthogonal to the retention one.
   *
   * <p>Called <em>lifecycle</em> on the wire, not "state": the advanced filter has its own,
   * finer-grained workflow-state parameter, and two things named `state` meaning different slices
   * of the same column is a trap rather than a saving.
   */
  enum Lifecycle {
    ANY,
    OPEN,
    CLOSED;

    static Lifecycle of(String raw) {
      return switch (raw == null ? "" : raw.toLowerCase(Locale.ROOT)) {
        case "open" -> OPEN;
        case "closed" -> CLOSED;
        default -> ANY;
      };
    }
  }

  /** The due-date slice, mirroring the client's `matchesDue`. */
  enum Due {
    ANY,
    OVERDUE,
    SOON,
    NONE;

    static Due of(String raw) {
      return switch (raw == null ? "" : raw.toLowerCase(Locale.ROOT)) {
        case "overdue" -> OVERDUE;
        case "soon" -> SOON;
        case "none" -> NONE;
        default -> ANY;
      };
    }
  }

  /** How far ahead "due soon" reaches, matching the client's window. */
  private static final int DUE_SOON_DAYS = 7;

  /** MIME families the format facet offers, in the DocumentIcon's language. */
  private static final Map<String, List<String>> FORMATS =
      Map.of(
          "pdf", List.of("application/pdf"),
          "docx",
              List.of(
                  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                  "application/msword"),
          "md", List.of("text/markdown"));

  /** Everything the moderation listing can be narrowed by (issue #563). */
  record Facets(
      String like,
      Scope scope,
      Lifecycle lifecycle,
      Role role,
      String workflowState,
      Due due,
      String format,
      UUID ownerId) {

    /** The same filter with one dimension swapped — how a chip's own count is taken. */
    Facets withRole(Role other) {
      return new Facets(like, scope, lifecycle, other, workflowState, due, format, ownerId);
    }

    Facets withStatus(Scope otherScope, Lifecycle otherLifecycle) {
      return new Facets(
          like, otherScope, otherLifecycle, role, workflowState, due, format, ownerId);
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

  static Specification<Document> of(UUID actor, Facets facets, Instant now) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (facets.like() != null) {
        predicates.add(cb.like(cb.lower(root.get("title")), facets.like()));
      }
      Scope scope = facets.scope();
      Lifecycle state = facets.lifecycle();
      Role role = facets.role();
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
      if (facets.workflowState() != null) {
        predicates.add(cb.equal(root.get("workflowState"), facets.workflowState()));
      }
      if (facets.ownerId() != null) {
        predicates.add(cb.equal(root.get("ownerId"), facets.ownerId()));
      }
      addDue(predicates, root, cb, facets.due(), now);
      List<String> mimes = facets.format() == null ? null : FORMATS.get(facets.format());
      if (mimes != null) {
        // The LATEST version's type, which is what the row's ribbon shows — a
        // review whose first version was a PDF and whose current one is Word is
        // a Word review.
        Subquery<UUID> latest = query.subquery(UUID.class);
        Root<DocumentVersion> version = latest.from(DocumentVersion.class);
        Subquery<Integer> maxNumber = latest.subquery(Integer.class);
        Root<DocumentVersion> any = maxNumber.from(DocumentVersion.class);
        maxNumber
            .select(cb.max(any.get("versionNumber")))
            .where(cb.equal(any.get("documentId"), root.get("id")));
        latest
            .select(version.get("id"))
            .where(
                cb.equal(version.get("documentId"), root.get("id")),
                cb.equal(version.get("versionNumber"), maxNumber),
                version.get("contentType").in(mimes));
        predicates.add(cb.exists(latest));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  /**
   * The due-date slice.
   *
   * <p>Only living deadlines: a finalized or archived review is never "overdue", which is the same
   * reading the client's `matchesDue` uses. `none` is the opposite question — work nobody has put a
   * clock on — and applies whatever the state.
   */
  private static void addDue(
      List<Predicate> predicates,
      Root<Document> root,
      jakarta.persistence.criteria.CriteriaBuilder cb,
      Due due,
      Instant now) {
    if (due == Due.ANY) {
      return;
    }
    if (due == Due.NONE) {
      predicates.add(cb.isNull(root.get("dueAt")));
      return;
    }
    predicates.add(cb.isNotNull(root.get("dueAt")));
    predicates.add(cb.isNull(root.get("archivedAt")));
    predicates.add(cb.not(root.get("workflowState").in(CLOSED)));
    if (due == Due.OVERDUE) {
      predicates.add(cb.lessThan(root.<Instant>get("dueAt"), now));
    } else {
      predicates.add(
          cb.between(root.<Instant>get("dueAt"), now, now.plus(DUE_SOON_DAYS, ChronoUnit.DAYS)));
    }
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
