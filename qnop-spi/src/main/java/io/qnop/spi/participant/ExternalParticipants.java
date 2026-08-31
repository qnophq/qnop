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
package io.qnop.spi.participant;

import java.util.UUID;

/**
 * The core-implemented facade for participants without a user account (issue #684, ADR-0061).
 *
 * <p>An external participant is a review-scoped principal: the returned id is its identity
 * everywhere the review domain speaks of an actor — authorship, access checks, visit records,
 * anonymity ordinals (ADR-0038 treats it like any other non-owner). It is <em>not</em> a user: it
 * has no profile, no slug, no avatar, cannot be mentioned and can never act outside its one review.
 * What the id stands for — a link, its expiry, who may redeem it — is entirely the extension's
 * business; the core never learns of credentials or invitations.
 *
 * <p>All methods are transactional and safe to call from any thread.
 */
public interface ExternalParticipants {

  /**
   * Adds an account-less participant to a review and returns its principal id.
   *
   * @param documentId the review; unknown ids are refused
   * @param displayName the name shown where the review shows participants (1–120 chars after
   *     trimming; blank refused). In anonymous reviews it is replaced by the usual "Participant N"
   *     pseudonym for everyone but the owner — nothing an extension supplies here can pierce
   *     ADR-0038.
   * @throws IllegalArgumentException for an unknown document or an unusable display name
   */
  UUID add(UUID documentId, String displayName);

  /**
   * Removes an external participant. Only principals created by {@link #add} are removable through
   * this facade — user and team rows are refused, so an extension cannot manage the account-bearing
   * roster.
   *
   * @return whether a row was removed ({@code false} for an unknown id)
   * @throws IllegalArgumentException when the id names a user or team participant
   */
  boolean remove(UUID documentId, UUID participantId);

  /**
   * Whether the principal may access the review — the core's one participant access rule, so it
   * answers {@code true} for any participating principal (an external's row id, but also a user id
   * or a member of a participating team). Callers gating a guest credential should pass the
   * principal id they issued via {@link #add}.
   */
  boolean hasAccess(UUID documentId, UUID participantId);
}
