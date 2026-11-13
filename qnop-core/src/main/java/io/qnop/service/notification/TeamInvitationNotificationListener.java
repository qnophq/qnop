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

import io.qnop.service.TeamInvitationCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges committed team invitations to {@link TeamInvitationNotifier} (issue #948), the same way
 * review events reach their notifications: {@code AFTER_COMMIT} so a rolled-back invitation tells
 * nobody, {@code @Async} on the notification executor so SMTP latency never sits in the request.
 * Failures are logged and swallowed — the invitation itself already exists and is on "My Teams".
 */
@Component
public class TeamInvitationNotificationListener {

  private static final Logger log =
      LoggerFactory.getLogger(TeamInvitationNotificationListener.class);

  private final TeamInvitationNotifier notifier;

  public TeamInvitationNotificationListener(TeamInvitationNotifier notifier) {
    this.notifier = notifier;
  }

  @Async("reviewNotificationExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void on(TeamInvitationCreated event) {
    try {
      notifier.dispatch(event);
    } catch (RuntimeException ex) {
      log.warn("team invitation notification failed for team {}", event.teamId(), ex);
    }
  }
}
