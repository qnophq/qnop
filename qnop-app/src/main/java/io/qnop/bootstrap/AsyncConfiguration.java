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
package io.qnop.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async plumbing for the app (issue #316). The only async consumer today is the review notification
 * listener; it gets a small, bounded executor of its own so a slow SMTP server can back up
 * notification mails without touching request threads or the scheduler.
 */
@Configuration
@EnableAsync
public class AsyncConfiguration {

  /** Bounded executor for review notification mails; overflow runs in the caller thread. */
  @Bean(name = "reviewNotificationExecutor")
  @ConditionalOnProperty(
      name = "qnop.notifications.sync-dispatch",
      havingValue = "false",
      matchIfMissing = true)
  public ThreadPoolTaskExecutor reviewNotificationExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("review-notify-");
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(200);
    // CallerRunsPolicy: if the queue is full the committing thread sends the mail itself —
    // slower, but nothing is silently dropped.
    executor.setRejectedExecutionHandler(
        new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    return executor;
  }

  /**
   * Integration tests flip {@code qnop.notifications.sync-dispatch} to run the listener in the
   * committing thread: deterministic assertions, and no async reader racing the per-test TRUNCATE.
   */
  @Bean(name = "reviewNotificationExecutor")
  @ConditionalOnProperty(name = "qnop.notifications.sync-dispatch", havingValue = "true")
  public TaskExecutor syncReviewNotificationExecutor() {
    return new SyncTaskExecutor();
  }

  /**
   * Dispatches the published event stream (issue #685, ADR-0059). Bounded, and full means DROP
   * (warn-logged) rather than CallerRuns: an extension's slow listener must never be able to slow
   * the committing request thread — delivery is best-effort by contract, and a consumer needing
   * durability queues on its own side.
   */
  @Bean(name = "publishedEventExecutor")
  @ConditionalOnProperty(
      name = "qnop.events.sync-dispatch",
      havingValue = "false",
      matchIfMissing = true)
  public ThreadPoolTaskExecutor publishedEventExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("published-event-");
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(500);
    executor.setRejectedExecutionHandler(
        (task, pool) ->
            org.slf4j.LoggerFactory.getLogger(AsyncConfiguration.class)
                .warn("published-event queue full — dropping one event dispatch"));
    return executor;
  }

  /** Synchronous variant for tests that assert on delivered events. */
  @Bean(name = "publishedEventExecutor")
  @ConditionalOnProperty(name = "qnop.events.sync-dispatch", havingValue = "true")
  public TaskExecutor syncPublishedEventExecutor() {
    return new SyncTaskExecutor();
  }
}
