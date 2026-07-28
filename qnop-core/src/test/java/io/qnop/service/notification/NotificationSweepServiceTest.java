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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.repository.NotificationRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.scheduler.SchedulerService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * DB-free behaviour of the retention sweep (issue #626): the disable switch, the dry-run's promise
 * to change nothing, and the batching that keeps a long-overdue first run from becoming one
 * enormous statement. A mock transaction manager runs each {@code TransactionTemplate} callback
 * in-line, so the loop is exercised without a database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationSweepServiceTest {

  private static final int BATCH_SIZE = 500;

  @Mock private NotificationRepository notifications;
  @Mock private ApplicationSettingsService settings;
  @Mock private SchedulerService scheduler;
  @Mock private PlatformTransactionManager transactionManager;

  private NotificationSweepService service;

  @BeforeEach
  void setUp() {
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service = new NotificationSweepService(notifications, settings, scheduler, transactionManager);
  }

  private void retainDays(int days) {
    when(settings.getInteger(ApplicationSettingKey.NOTIFICATIONS_RETAIN_DAYS)).thenReturn(days);
  }

  @Test
  @DisplayName("a retention of 0 disables pruning and touches nothing")
  void disabled() {
    retainDays(0);

    assertThat(service.sweepOnce(false)).contains("disabled");

    verifyNoInteractions(notifications);
  }

  @Test
  @DisplayName("a dry run reports the count and deletes nothing")
  void dryRunDeletesNothing() {
    retainDays(90);
    when(notifications.countByCreatedAtBefore(any())).thenReturn(42L);

    assertThat(service.sweepOnce(true)).contains("42").contains("nothing was changed");

    verify(notifications, never()).deleteOlderThan(any(), anyInt());
  }

  @Test
  @DisplayName("the cutoff is the retention window before now")
  void cutoffFollowsTheWindow() {
    retainDays(30);
    when(notifications.countByCreatedAtBefore(any())).thenReturn(0L);
    Instant before = Instant.now();

    service.sweepOnce(true);

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(notifications).countByCreatedAtBefore(cutoff.capture());
    // ~30 days back, generously bounded so the assertion is not a clock race.
    assertThat(cutoff.getValue())
        .isBefore(before.minusSeconds(29L * 86_400))
        .isAfter(before.minusSeconds(31L * 86_400));
  }

  @Test
  @DisplayName("a short batch ends the run — nothing older is left")
  void stopsWhenTheBacklogIsDrained() {
    retainDays(90);
    when(notifications.deleteOlderThan(any(), anyInt())).thenReturn(120);

    assertThat(service.sweepOnce(false)).isEqualTo("Deleted 120 notification(s)");

    // One partial batch is proof the backlog is gone; a second statement would
    // be a pointless round-trip.
    verify(notifications, times(1)).deleteOlderThan(any(), anyInt());
  }

  @Test
  @DisplayName("nothing due reports so rather than claiming a deletion")
  void nothingDue() {
    retainDays(90);
    when(notifications.deleteOlderThan(any(), anyInt())).thenReturn(0);

    assertThat(service.sweepOnce(false)).contains("No notifications older than 90");
  }

  @Test
  @DisplayName("a full batch keeps going, and a run that hits its cap says the rest follows")
  void keepsGoingAndReportsTheCap() {
    retainDays(90);
    // Every batch comes back full: the backlog outlives this run's cap.
    when(notifications.deleteOlderThan(any(), anyInt())).thenReturn(BATCH_SIZE);

    String summary = service.sweepOnce(false);

    // Silence here would read as "done" to an operator; it is not.
    assertThat(summary).contains("more remain for the next run");
    verify(notifications, times(40)).deleteOlderThan(any(), anyInt());
  }
}
