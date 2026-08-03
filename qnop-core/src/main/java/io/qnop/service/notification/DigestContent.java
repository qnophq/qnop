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
package io.qnop.service.notification;

import io.qnop.entity.Notification;
import io.qnop.entity.NotificationType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one recipient's digest says (issue #680) — a summary, not a concatenated log.
 *
 * <p>Deliberately free of database and mail: given a list of notifications it decides what the mail
 * will claim, which is the part worth testing exhaustively. Nothing here queries or sends.
 *
 * <p>Grouped per document: a counted headline first, because "3 new annotations, 7 comments" is the
 * sentence somebody can act on, then the events themselves in the order they happened, so the
 * reader can see what actually went on without opening the review.
 */
public record DigestContent(List<DocumentSummary> documents, int total, Instant coveredThrough) {

  /** One document's share of the digest: the headline counts, then what happened, oldest first. */
  public record DocumentSummary(
      UUID documentId, Map<NotificationType, Integer> counts, int total, List<Event> events) {}

  /**
   * One thing that happened.
   *
   * <p>The actor travels as an id, never a name: under per-review anonymity (ADR-0038) a name
   * belongs to a (review, viewer) pair, so resolving it is the caller's job and must not be guessed
   * here.
   */
  public record Event(
      Instant at, NotificationType type, UUID actorId, String excerpt, Integer versionNumber) {}

  /**
   * Builds the summary. Documents keep the order of their first unread notification, so the digest
   * reads oldest-first like the inbox it summarises.
   *
   * @param notifications one recipient's unread notifications, oldest first
   */
  public static DigestContent of(List<Notification> notifications) {
    Map<UUID, Map<NotificationType, Integer>> counts = new LinkedHashMap<>();
    Map<UUID, List<Event>> events = new LinkedHashMap<>();
    Instant newest = null;
    for (Notification notification : notifications) {
      // Notifications without a document (none today, but the type allows it) are
      // counted under a null key rather than dropped: a digest that silently omits
      // something is worse than one with an odd heading.
      UUID documentId = notification.getDocumentId();
      counts
          .computeIfAbsent(documentId, id -> new EnumMap<>(NotificationType.class))
          .merge(notification.getType(), 1, Integer::sum);
      events
          .computeIfAbsent(documentId, id -> new ArrayList<>())
          .add(
              new Event(
                  notification.getCreatedAt(),
                  notification.getType(),
                  notification.getActorId(),
                  notification.getExcerpt(),
                  notification.getVersionNumber()));
      Instant createdAt = notification.getCreatedAt();
      if (createdAt != null && (newest == null || createdAt.isAfter(newest))) {
        newest = createdAt;
      }
    }

    List<DocumentSummary> documents = new ArrayList<>();
    int total = 0;
    for (Map.Entry<UUID, Map<NotificationType, Integer>> entry : counts.entrySet()) {
      int documentTotal = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
      documents.add(
          new DocumentSummary(
              entry.getKey(),
              Map.copyOf(entry.getValue()),
              documentTotal,
              List.copyOf(events.getOrDefault(entry.getKey(), List.of()))));
      total += documentTotal;
    }
    return new DigestContent(List.copyOf(documents), total, newest);
  }

  /** Nothing to say — and an empty digest is worse than silence, so no mail goes out. */
  public boolean isEmpty() {
    return total == 0;
  }
}
