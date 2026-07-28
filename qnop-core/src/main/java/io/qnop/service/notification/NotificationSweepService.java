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

import io.qnop.repository.NotificationRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the in-app inbox bounded (issue #626).
 *
 * <p>ADR-0051 chose fan-out on write — one row per recipient per event — which is what makes the
 * inbox trivial to read and unbounded to store. This sweep is the other half: notifications older
 * than {@code notifications.retain_days} are deleted, <strong>read or not</strong>.
 *
 * <p>Deleting unread ones too is deliberate. A retention that any user can defeat by never opening
 * their inbox is not a retention policy; and nothing here is lost that cannot be found again — the
 * review, the annotation and the comment all survive, only the record that they were once announced
 * goes.
 *
 * <p>Unlike the review purge (ADR-0050) this destroys nothing irreversible in that sense, so it
 * ships <em>enabled</em>: the retention window is the real control, and {@code 0} switches it off.
 */
@Service
public class NotificationSweepService {

  private static final Logger log = LoggerFactory.getLogger(NotificationSweepService.class);

  /** Rows per statement — the cap that keeps a long-overdue first run from becoming one lock. */
  private static final int BATCH_SIZE = 500;

  /**
   * Batches per run. A backlog larger than this simply finishes on the following runs, which is
   * preferable to one sweep holding the table for minutes.
   */
  private static final int MAX_BATCHES = 40;

  private final NotificationRepository notifications;
  private final ApplicationSettingsService settings;
  private final SchedulerService scheduler;
  private final TransactionTemplate tx;

  public NotificationSweepService(
      NotificationRepository notifications,
      ApplicationSettingsService settings,
      SchedulerService scheduler,
      PlatformTransactionManager transactionManager) {
    this.notifications = notifications;
    this.settings = settings;
    this.scheduler = scheduler;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** Off-peak cron entry point, after the review sweeps; the gate runs {@link #sweepOnce}. */
  @Scheduled(cron = "${qnop.notifications.sweep-cron:0 15 4 * * *}")
  @SchedulerLock(name = SchedulerJobCatalog.NOTIFICATION_SWEEP, lockAtMostFor = "PT20M")
  public void sweep() {
    scheduler.runScheduled(SchedulerJobCatalog.NOTIFICATION_SWEEP);
  }

  /**
   * One retention pass. Invoked by the gate <em>outside</em> any transaction (the job is
   * self-transactional), so each batch commits on its own and an interrupted run keeps the work it
   * already did. In {@code dryRun} mode it reports what it would delete and changes nothing.
   */
  public String sweepOnce(boolean dryRun) {
    int retainDays = settings.getInteger(ApplicationSettingKey.NOTIFICATIONS_RETAIN_DAYS);
    if (retainDays <= 0) {
      log.info("Notification pruning is disabled (notifications.retain_days={}).", retainDays);
      return "Pruning is disabled (notifications.retain_days=" + retainDays + ")";
    }
    Instant cutoff = Instant.now().minus(Duration.ofDays(retainDays));

    if (dryRun) {
      Long due = tx.execute(status -> notifications.countByCreatedAtBefore(cutoff));
      long count = due == null ? 0 : due;
      log.info(
          "Notification sweep (dry-run) would delete {} notification(s) older than {}; nothing was"
              + " changed.",
          count,
          cutoff);
      return "Would delete " + count + " notification(s); nothing was changed";
    }

    int deleted = 0;
    boolean more = true;
    for (int batch = 0; batch < MAX_BATCHES && more; batch++) {
      Integer removed = tx.execute(status -> notifications.deleteOlderThan(cutoff, BATCH_SIZE));
      int count = removed == null ? 0 : removed;
      deleted += count;
      // A short batch means the backlog is drained; a full one means keep going.
      more = count == BATCH_SIZE;
    }

    if (deleted == 0) {
      return "No notifications older than " + retainDays + " day(s)";
    }
    if (more) {
      // Deliberately not silent: a run that stopped at its cap did not finish, and
      // an operator reading "deleted 20000" should know the rest follows tomorrow.
      log.info(
          "Notification sweep deleted {} notification(s) older than {} and hit its per-run cap;"
              + " the remainder follows on the next run.",
          deleted,
          cutoff);
      return "Deleted " + deleted + " notification(s); more remain for the next run";
    }
    log.info("Notification sweep deleted {} notification(s) older than {}.", deleted, cutoff);
    return "Deleted " + deleted + " notification(s)";
  }
}
