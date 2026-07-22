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

/**
 * A refused workflow action (issue #246, ADR-0011): an illegal edge, a vetoing guard (e.g. the
 * FINALIZED invariant), resolving a non-OPEN annotation, annotating a closed review, or a state
 * this edition does not manage. Carries a stable {@code code} for the uniform error envelope;
 * mapped to HTTP 409.
 */
public class WorkflowTransitionException extends RuntimeException {

  /** Stable machine-readable code for the error envelope. */
  public static final String INVALID_TRANSITION = "INVALID_TRANSITION";

  /** A resolution was requested on an annotation that is no longer {@code OPEN}. */
  public static final String ANNOTATION_ALREADY_RESOLVED = "ANNOTATION_ALREADY_RESOLVED";

  public static final String PLACEMENT_NOT_MOVED = "PLACEMENT_NOT_MOVED";

  public static final String PLACEMENT_NOT_REATTACHABLE = "PLACEMENT_NOT_REATTACHABLE";

  /** An annotation was raised on a review that is already FINALIZED or CANCELLED (issue #405). */
  public static final String REVIEW_CLOSED = "REVIEW_CLOSED";

  /** A reopen was requested on an annotation that is not RESOLVED (issue #394). */
  public static final String ANNOTATION_NOT_RESOLVED = "ANNOTATION_NOT_RESOLVED";

  /** A dismissal was requested on an annotation that is no longer {@code OPEN} (issue #408). */
  public static final String ANNOTATION_NOT_OPEN = "ANNOTATION_NOT_OPEN";

  private final String code;

  public WorkflowTransitionException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
