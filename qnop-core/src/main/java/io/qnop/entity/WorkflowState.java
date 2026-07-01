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
package io.qnop.entity;

import java.util.Optional;

/**
 * The lifecycle state of a review (issue #244, ADR-0011). The happy path is {@code DRAFT →
 * IN_REVIEW ⇄ CHANGES_REQUESTED → FINALIZED}, with {@code CANCELLED} reachable as an abort.
 *
 * <p><strong>Deliberately extensible.</strong> Unlike the other status enums, the workflow state is
 * persisted as a plain {@code VARCHAR} with <em>no</em> closed DB {@code CHECK} (ADR-0011): the
 * Community edition ships these five states, but the column tolerates additional states an
 * enterprise edition may introduce (e.g. a signing gate before finalization, ADR-0035) via a
 * configurable state machine (planned for #246). Community code references this enum for the states
 * it knows; {@link #fromString(String)} tolerates unknown (enterprise) values rather than throwing.
 */
public enum WorkflowState {
  /** Being prepared by the owner; not yet open to reviewers. */
  DRAFT,
  /** Open for reviewers to annotate and comment. */
  IN_REVIEW,
  /** The owner requested changes; a new version is expected before review resumes. */
  CHANGES_REQUESTED,
  /** Review complete — no open annotations remain and the owner finalized it. */
  FINALIZED,
  /** Aborted; kept for the record but no longer active. */
  CANCELLED;

  /**
   * Resolves a persisted state string to a known Community state, if it is one. Returns an empty
   * {@link Optional} for values this edition does not recognize (e.g. an enterprise-only state)
   * rather than throwing, so Community code can read reviews driven by an extended state machine.
   */
  public static Optional<WorkflowState> fromString(String value) {
    if (value == null) {
      return Optional.empty();
    }
    for (WorkflowState state : values()) {
      if (state.name().equals(value)) {
        return Optional.of(state);
      }
    }
    return Optional.empty();
  }
}
