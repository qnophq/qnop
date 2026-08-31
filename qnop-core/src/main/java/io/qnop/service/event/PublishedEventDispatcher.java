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

import io.qnop.entity.Document;
import io.qnop.repository.DocumentRepository;
import io.qnop.service.review.ReviewEvent;
import io.qnop.spi.event.PublishedEvent;
import io.qnop.spi.event.PublishedEventListener;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the internal {@link ReviewEvent} stream to the published catalogue (issue #685,
 * ADR-0059). {@code AFTER_COMMIT} so a rolled-back action is never announced; {@code @Async} on a
 * dedicated bounded executor so no listener ever runs on the request thread; each listener wrapped
 * so one that throws costs itself, never its siblings or the action. With no registered listener
 * (the Community default) the mapping is skipped entirely.
 *
 * <p><strong>Anonymity (ADR-0038):</strong> in an anonymous review the actor's identity never
 * enters the published stream — {@code actorId} is nulled before any listener hears the event. The
 * flag is read from the document (or carried by {@link ReviewEvent.ReviewDeleted}, whose subject no
 * longer exists); a document that cannot be loaded is treated as anonymous, erring on the side of
 * silence.
 */
@Component
public class PublishedEventDispatcher {

  private static final Logger log = LoggerFactory.getLogger(PublishedEventDispatcher.class);

  private final List<PublishedEventListener> listeners;
  private final DocumentRepository documents;

  public PublishedEventDispatcher(
      List<PublishedEventListener> listeners, DocumentRepository documents) {
    this.listeners = List.copyOf(listeners);
    this.documents = documents;
  }

  private boolean isAnonymous(ReviewEvent event) {
    if (event instanceof ReviewEvent.ReviewDeleted deleted) {
      return deleted.anonymous();
    }
    return documents.findById(event.documentId()).map(Document::isAnonymous).orElse(true);
  }

  @Async("publishedEventExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void on(ReviewEvent event) {
    if (listeners.isEmpty()) {
      return;
    }
    PublishedEvent published = PublishedEventMapper.map(event, Instant.now(), isAnonymous(event));
    for (PublishedEventListener listener : listeners) {
      try {
        listener.on(published);
      } catch (RuntimeException ex) {
        log.warn(
            "published-event listener {} failed for {} on document {}",
            listener.getClass().getName(),
            published.type(),
            published.documentId(),
            ex);
      }
    }
  }
}
