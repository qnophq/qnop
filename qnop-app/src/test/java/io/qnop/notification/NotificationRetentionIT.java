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
package io.qnop.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Notification;
import io.qnop.entity.NotificationType;
import io.qnop.repository.NotificationRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.notification.NotificationSweepService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The retention sweep against a real database (issue #626): the age boundary, the disable switch,
 * the dry-run's promise, and the job's presence on the scheduler dashboard.
 *
 * <p>{@code created_at} is set by {@code @CreationTimestamp}, so ageing a row means an UPDATE
 * straight through JDBC — the entity deliberately offers no setter for it.
 */
class NotificationRetentionIT extends SeededIntegrationTest {

  @Autowired private NotificationRepository notifications;
  @Autowired private NotificationSweepService sweep;
  @Autowired private ApplicationSettingsService settings;
  @Autowired private JdbcTemplate jdbc;

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  /** A notification for MEMBER, aged by writing created_at directly. */
  private UUID seedNotification(int daysOld, boolean read) {
    Notification saved =
        notifications.save(Notification.of(MEMBER_ID, NotificationType.COMMENT_ADDED));
    if (read) {
      saved.markRead(Instant.now());
      notifications.save(saved);
    }
    jdbc.update(
        "UPDATE notification SET created_at = ? WHERE id = ?",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(daysOld))),
        saved.getId());
    return saved.getId();
  }

  private void retainDays(int days) {
    settings.update(
        java.util.Map.of(
            ApplicationSettingKey.NOTIFICATIONS_RETAIN_DAYS.getKey(), String.valueOf(days)),
        ADMIN_ID);
  }

  @Test
  @DisplayName("notifications past the window go, newer ones stay — read or not")
  void prunesByAgeRegardlessOfReadState() {
    retainDays(90);
    UUID oldRead = seedNotification(200, true);
    UUID oldUnread = seedNotification(200, false);
    UUID recentUnread = seedNotification(10, false);

    String summary = sweep.sweepOnce(false);

    assertThat(summary).contains("Deleted 2 notification(s)");
    assertThat(notifications.findById(oldRead)).isEmpty();
    // Unread is no shield: a retention any user could defeat by never opening
    // their inbox would not be a retention policy (ADR-0051 amendment).
    assertThat(notifications.findById(oldUnread)).isEmpty();
    assertThat(notifications.findById(recentUnread)).isPresent();
  }

  @Test
  @DisplayName("a retention of 0 keeps everything, however old")
  void zeroDisablesPruning() {
    retainDays(0);
    UUID ancient = seedNotification(3650, true);

    assertThat(sweep.sweepOnce(false)).contains("disabled");

    assertThat(notifications.findById(ancient)).isPresent();
  }

  @Test
  @DisplayName("a dry run counts what is due and deletes nothing")
  void dryRunChangesNothing() {
    retainDays(30);
    UUID due = seedNotification(100, true);

    assertThat(sweep.sweepOnce(true)).contains("1").contains("nothing was changed");

    assertThat(notifications.findById(due)).isPresent();
  }

  @Test
  @DisplayName("the sweep is on the dashboard, dry-run capable and enabled by default")
  void appearsOnTheSchedulerDashboard() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/admin/scheduler"), ADMIN_ID))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[?(@.jobId=='notificationSweep')].displayName")
                .value("Notification sweep"))
        .andExpect(jsonPath("$.items[?(@.jobId=='notificationSweep')].supportsDryRun").value(true))
        // Unlike the review purge it needs no second opt-in — it removes only the
        // record that something was announced, never the thing itself.
        .andExpect(jsonPath("$.items[?(@.jobId=='notificationSweep')].enabled").value(true));
  }

  @Test
  @DisplayName("a manual run reports its summary on the job row")
  void runNowReportsItsSummary() throws Exception {
    retainDays(30);
    seedNotification(100, true);

    mockMvc
        .perform(
            as(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/admin/scheduler/notificationSweep/run"),
                ADMIN_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastOutcome").value("SUCCESS"))
        .andExpect(
            jsonPath("$.lastDetail").value(org.hamcrest.Matchers.containsString("Deleted 1")));
  }
}
