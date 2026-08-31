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
package io.qnop.spi.review;

import java.util.Set;
import java.util.UUID;

/**
 * The core-implemented, read-only facade over a review's scoping rules (issue #602, ADR-0062) —
 * what an extension needs to serve a live feed (qnop-ee#5) without touching internals.
 *
 * <p>All answers are computed fresh from the same code paths the core itself uses, so they cannot
 * drift from the REST API's behaviour. Admin override is deliberately absent: an extension serves
 * regular principals; an operator uses the API.
 */
public interface ReviewFacade {

  /**
   * Whether the principal may view the review — exactly the rule the annotation-listing endpoint
   * applies (owner, direct participant, member of a participating team, or an account-less
   * participant's principal id per ADR-0061).
   */
  boolean mayView(UUID documentId, UUID principalId);

  /**
   * The review circle the notification path addresses: the owner, direct user participants and the
   * members of participating teams — user ids only, in stable order. Account-less participants are
   * deliberately not included (they have no notification identity); an extension admits them
   * through {@code ExternalParticipants.hasAccess} instead. The actor of an event is NOT excluded
   * here — a consumer that must not echo an event to its actor subtracts the {@code actorId} the
   * published event carries.
   */
  Set<UUID> reviewCircle(UUID documentId);

  /**
   * The author's display name as {@code viewerId} may see it (ADR-0038): the real name in normal
   * reviews, the "Participant N" pseudonym in anonymous ones unless the author is the owner or the
   * viewer themselves. Never null; unknown authors read as a generic participant.
   */
  String displayNameFor(UUID documentId, UUID viewerId, UUID authorId);

  /**
   * The author id as {@code viewerId} may see it (ADR-0038): the real id, or the review-scoped
   * synthetic pseudonym id in anonymous reviews. What a consumer forwards outward must be THIS
   * value, never the raw author id.
   */
  UUID exposedAuthorIdFor(UUID documentId, UUID viewerId, UUID authorId);
}
