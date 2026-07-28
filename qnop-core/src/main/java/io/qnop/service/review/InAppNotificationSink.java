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

import io.qnop.entity.Notification;
import io.qnop.repository.NotificationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The in-app channel (issue #538, ADR-0051): one persisted row per recipient.
 *
 * <p>It accepts everything it is offered. The two conditions that genuinely apply — the recipient
 * is an enabled user and is not the actor — are structural and already settled during resolution;
 * the e-mail switch, the address requirement and the mail opt-outs are the <em>mail</em> sink's
 * business (see {@link MailNotificationSink}). Per-type in-app preferences, when they arrive,
 * belong in {@link #accepts} and nowhere else.
 *
 * <p>The row stores ids, never names: the actor's display name is resolved per recipient when the
 * notification is read, so an anonymous review (ADR-0038) stays anonymous even if its privacy
 * setting changes afterwards.
 */
@Component
public class InAppNotificationSink implements ReviewNotificationSink {

  private final NotificationRepository notifications;

  public InAppNotificationSink(NotificationRepository notifications) {
    this.notifications = notifications;
  }

  @Override
  public boolean accepts(ReviewNotificationIntent intent) {
    return true;
  }

  /**
   * Writes the row in its own transaction: the dispatcher resolves inside a read-only one, and a
   * write that fails for one recipient must not take the rest of the fan-out — or the mail — with
   * it.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deliver(ReviewNotificationIntent intent) {
    notifications.save(
        Notification.of(intent.recipient().getId(), intent.type())
            .withActor(intent.actorId())
            .withDocument(intent.documentId())
            .withAnnotation(intent.annotationId())
            .withComment(intent.commentId())
            .withExcerpt(intent.excerpt())
            .withDecision(intent.decision())
            .withVersionNumber(intent.versionNumber())
            .withTransition(intent.fromState(), intent.toState()));
  }
}
