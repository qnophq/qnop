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
package io.qnop.service.review;

import io.qnop.entity.PlacementStatus;

/**
 * Decides whether a placement may be re-attached (ADR-0009, issues #457/#479/#562), given the
 * actor's relation to the annotation and the operator's free-re-attach switch. Pure and DB-free so
 * the full setting × status × actor matrix is unit-testable.
 *
 * <p>Rules: {@code PENDING} never (re-anchoring is in flight). The lost placements ({@code
 * ORPHANED}/{@code FAILED}/{@code MOVED}) are always rescuable — by whoever passed the caller's
 * outer authorization (owner, author or admin). A healthy {@code PLACED} placement may be
 * re-positioned by an <em>admin</em> at any time, and by the annotation's <em>author</em> when the
 * operator enabled {@code review.free_reattach_enabled} (#562); everyone else is refused so nothing
 * is overwritten by accident.
 */
final class PlacementReattachPolicy {

  private PlacementReattachPolicy() {}

  static boolean reattachEligible(
      PlacementStatus status, boolean actorIsAuthor, boolean actorIsAdmin, boolean freeReattach) {
    return switch (status) {
      case PENDING -> false;
      case PLACED -> actorIsAdmin || (freeReattach && actorIsAuthor);
      case ORPHANED, FAILED, MOVED -> true;
    };
  }
}
