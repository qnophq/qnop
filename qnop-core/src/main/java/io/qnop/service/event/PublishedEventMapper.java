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
package io.qnop.service.event;

import io.qnop.service.review.ReviewEvent;
import io.qnop.spi.event.PublishedEvent;
import io.qnop.spi.event.PublishedEventTypes;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The one place internal {@link ReviewEvent}s become catalogued {@link PublishedEvent}s (issue
 * #685, ADR-0059). Identifiers only — no bodies, no titles: whether a consumer may read the subject
 * is the API's permission question, not the event stream's. Exhaustive over the sealed hierarchy,
 * so a new internal event is a compile error here — the author decides deliberately whether it
 * enters the published catalogue.
 */
final class PublishedEventMapper {

  private PublishedEventMapper() {}

  /**
   * Attribute keys that identify a person or team. In an anonymous review no identity — actor or
   * subject — crosses the seam (ADR-0038), so these are stripped along with the actor. The owner
   * ({@code ownerId} on {@code review.deleted}) is exempt: ownership is structurally public under
   * ADR-0038.
   */
  private static final Set<String> IDENTITY_ATTRIBUTES = Set.of("userId", "teamId");

  static PublishedEvent map(ReviewEvent event, Instant occurredAt, boolean anonymous) {
    PublishedEvent published = map(event, occurredAt);
    if (!anonymous) {
      return published;
    }
    // ADR-0038: no identity — actor or subject — leaves an anonymous review.
    Map<String, String> attributes = new HashMap<>(published.attributes());
    attributes.keySet().removeAll(IDENTITY_ATTRIBUTES);
    return new PublishedEvent(
        published.type(), published.occurredAt(), published.documentId(), null, attributes);
  }

  private static PublishedEvent map(ReviewEvent event, Instant occurredAt) {
    return switch (event) {
      case ReviewEvent.AnnotationCreated e ->
          publish(
              e,
              occurredAt,
              PublishedEventTypes.ANNOTATION_CREATED,
              "annotationId",
              e.annotationId().toString());
      case ReviewEvent.AnnotationDecided e ->
          publish(
              e,
              occurredAt,
              PublishedEventTypes.ANNOTATION_DECIDED,
              "annotationId",
              e.annotationId().toString(),
              "reopened",
              Boolean.toString(e.reopened()));
      case ReviewEvent.AnnotationDismissed e ->
          publish(
              e,
              occurredAt,
              PublishedEventTypes.ANNOTATION_DISMISSED,
              "annotationId",
              e.annotationId().toString());
      case ReviewEvent.CommentAdded e ->
          publish(
              e,
              occurredAt,
              PublishedEventTypes.COMMENT_ADDED,
              "annotationId",
              e.annotationId().toString(),
              "commentId",
              e.commentId().toString());
      case ReviewEvent.VersionUploaded e ->
          publish(
              e,
              occurredAt,
              PublishedEventTypes.VERSION_UPLOADED,
              "versionNumber",
              Integer.toString(e.versionNumber()));
      case ReviewEvent.WorkflowChanged e ->
          publish(
              e,
              occurredAt,
              PublishedEventTypes.WORKFLOW_CHANGED,
              "fromState",
              e.fromState(),
              "toState",
              e.toState(),
              "manual",
              Boolean.toString(e.manual()));
      case ReviewEvent.ParticipantAdded e -> {
        Map<String, String> attributes = new HashMap<>();
        if (e.userId() != null) {
          attributes.put("userId", e.userId().toString());
        }
        if (e.teamId() != null) {
          attributes.put("teamId", e.teamId().toString());
        }
        yield new PublishedEvent(
            PublishedEventTypes.PARTICIPANT_ADDED,
            occurredAt,
            e.documentId(),
            e.actorId(),
            attributes);
      }
      case ReviewEvent.ReviewDeleted e ->
          // Deliberately no title: customer content stays out of the stream.
          publish(
              e, occurredAt, PublishedEventTypes.REVIEW_DELETED, "ownerId", e.ownerId().toString());
    };
  }

  private static PublishedEvent publish(
      ReviewEvent event, Instant occurredAt, String type, String... kv) {
    Map<String, String> attributes = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      attributes.put(kv[i], kv[i + 1]);
    }
    return new PublishedEvent(type, occurredAt, event.documentId(), event.actorId(), attributes);
  }
}
