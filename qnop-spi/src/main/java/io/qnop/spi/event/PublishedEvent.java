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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One catalogued review event (issue #685, ADR-0059): <em>what happened to which object</em>,
 * identifiers only. Bodies, titles and any other customer content are deliberately absent — whether
 * a consumer may read the subject is a permission question answered by the API, not by the event
 * stream.
 *
 * @param type a stable catalogued name from {@link PublishedEventTypes}; new names may appear in
 *     minor releases, so consumers must ignore types they do not know
 * @param occurredAt when the event was published (after the originating commit)
 * @param documentId the review the event belongs to — for {@link
 *     PublishedEventTypes#REVIEW_DELETED} the id of the review that no longer exists
 * @param actorId the user whose action raised the event
 * @param attributes per-type identifiers (see the constants' javadoc), immutable; values are
 *     rendered as strings ({@code UUID}s in canonical form, numbers and booleans via {@code
 *     toString})
 */
public record PublishedEvent(
    String type,
    Instant occurredAt,
    UUID documentId,
    UUID actorId,
    Map<String, String> attributes) {

  public PublishedEvent {
    attributes = Map.copyOf(attributes);
  }
}
