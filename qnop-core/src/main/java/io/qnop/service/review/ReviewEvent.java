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

import java.util.UUID;

/**
 * Review activity worth telling people about (issue #316), published on Spring's application event
 * bus by the review services inside their transactions and consumed by {@link
 * ReviewNotificationListener} after commit. Events carry ids only — the listener re-reads current
 * state, so a rolled-back transaction never leaks a mail and stale event payloads cannot lie.
 */
public sealed interface ReviewEvent {

  UUID documentId();

  /** The user whose action raised the event — never notified about their own action. */
  UUID actorId();

  /** A reviewer was added (issue #316): exactly one of {@code userId} / {@code teamId} is set. */
  record ParticipantAdded(UUID documentId, UUID actorId, UUID userId, UUID teamId)
      implements ReviewEvent {}

  /** A new annotation was raised. */
  record AnnotationCreated(UUID documentId, UUID actorId, UUID annotationId)
      implements ReviewEvent {}

  /** An annotation was decided: resolved, or reopened when {@code reopened}. */
  record AnnotationDecided(UUID documentId, UUID actorId, UUID annotationId, boolean reopened)
      implements ReviewEvent {}

  /** A reply landed in an annotation's thread. */
  record CommentAdded(UUID documentId, UUID actorId, UUID annotationId, UUID commentId)
      implements ReviewEvent {}

  /**
   * The review changed workflow state. {@code manual} distinguishes owner-initiated transitions
   * from the derived {@code IN_REVIEW ⇄ CHANGES_REQUESTED} pair (issue #405) — derived flips ride
   * along in the annotation mails that caused them, so only manual ones are mailed.
   */
  record WorkflowChanged(
      UUID documentId, UUID actorId, String fromState, String toState, boolean manual)
      implements ReviewEvent {}
}
