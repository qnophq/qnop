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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges committed {@link ReviewEvent}s to {@link ReviewNotificationService} (issue #316): {@code
 * AFTER_COMMIT} so a rolled-back action never mails anyone, {@code @Async} so SMTP latency never
 * sits in a request thread. Notification failures are logged and swallowed — mail is best-effort,
 * the review itself already happened.
 */
@Component
public class ReviewNotificationListener {

  private static final Logger log = LoggerFactory.getLogger(ReviewNotificationListener.class);

  private final ReviewNotificationService notifications;

  public ReviewNotificationListener(ReviewNotificationService notifications) {
    this.notifications = notifications;
  }

  @Async("reviewNotificationExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void on(ReviewEvent event) {
    try {
      notifications.dispatch(event);
    } catch (RuntimeException ex) {
      log.warn(
          "review notification failed for {} on document {}",
          event.getClass().getSimpleName(),
          event.documentId(),
          ex);
    }
  }
}
