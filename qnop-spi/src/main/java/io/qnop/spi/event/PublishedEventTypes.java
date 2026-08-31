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
package io.qnop.spi.event;

/**
 * The curated event catalogue (issue #685, ADR-0059). Deliberately short: adding a name is a
 * compatible change, removing or renaming one is breaking — so nothing enters this list without a
 * consumer. The names are the contract; the internal events they are mapped from may change freely.
 */
public final class PublishedEventTypes {

  /** Attributes: {@code annotationId}. */
  public static final String ANNOTATION_CREATED = "review.annotation.created";

  /** Attributes: {@code annotationId}, {@code reopened} ({@code true|false}). */
  public static final String ANNOTATION_DECIDED = "review.annotation.decided";

  /** Attributes: {@code annotationId}. */
  public static final String ANNOTATION_DISMISSED = "review.annotation.dismissed";

  /** Attributes: {@code annotationId}, {@code commentId}. */
  public static final String COMMENT_ADDED = "review.comment.added";

  /** Attributes: {@code versionNumber}. */
  public static final String VERSION_UPLOADED = "review.version.uploaded";

  /** Attributes: {@code fromState}, {@code toState}, {@code manual} ({@code true|false}). */
  public static final String WORKFLOW_CHANGED = "review.workflow.changed";

  /** Attributes: exactly one of {@code userId} / {@code teamId}. */
  public static final String PARTICIPANT_ADDED = "review.participant.added";

  /** Attributes: {@code ownerId}. The subject no longer exists when this is heard. */
  public static final String REVIEW_DELETED = "review.deleted";

  private PublishedEventTypes() {}
}
