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

/**
 * What a {@link Notification} is about (issue #538, ADR-0051) — the same vocabulary the review
 * events and their mail templates speak, so one resolution can feed both channels.
 *
 * <p>The declaration order is the delivery rank: resolution may offer several candidates for one
 * recipient, and each sink delivers the first it accepts. {@link #MENTION} therefore comes before
 * the {@code COMMENT_ADDED}/{@code ANNOTATION_CREATED} that contains it — being named in a comment
 * is the more specific thing that happened, and a mentioned follower should hear it once.
 */
public enum NotificationType {
  MENTION,
  PARTICIPANT_ADDED,
  ANNOTATION_CREATED,
  ANNOTATION_DECIDED,
  COMMENT_ADDED,
  VERSION_UPLOADED,
  WORKFLOW_CHANGED
}
