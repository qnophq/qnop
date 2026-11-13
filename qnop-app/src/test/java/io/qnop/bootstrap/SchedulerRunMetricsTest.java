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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import io.qnop.service.scheduler.SchedulerService.SchedulerJobView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When each scheduled job last ran and whether that run failed (issue #962). */
class SchedulerRunMetricsTest {

  private static final String RAN_OK = SchedulerJobCatalog.REVIEW_ARCHIVE;
  private static final String RAN_BADLY = SchedulerJobCatalog.AUDIT_CHAIN_VERIFY;
  private static final String NEVER_RAN = SchedulerJobCatalog.REVIEW_PURGE;
  private static final Instant LAST_RUN = Instant.parse("2026-11-13T03:50:00Z");

  private final SchedulerService scheduler = mock(SchedulerService.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private static SchedulerJobView view(String jobId, Instant lastRunAt, String lastOutcome) {
    return new SchedulerJobView(
        jobId, jobId, "", "0 0 * * * *", false, true, false, lastRunAt, lastOutcome, null, null);
  }

  private double gauge(String name, String jobId) {
    return registry.find(name).tag("job", jobId).gauge().value();
  }

  private void bind() {
    when(scheduler.list())
        .thenReturn(
            List.of(
                view(RAN_OK, LAST_RUN, "SUCCESS"),
                view(RAN_BADLY, LAST_RUN, "FAILURE"),
                view(NEVER_RAN, null, null)));
    new SchedulerRunMetrics(scheduler, new SteppingClock()).bindTo(registry);
  }

  @Test
  @DisplayName("publishes the last run of every catalogued job as a Unix timestamp")
  void publishesTheLastRun() {
    bind();

    assertThat(gauge("qnop.scheduler.last.run.timestamp", RAN_OK))
        .isEqualTo(LAST_RUN.getEpochSecond());
    assertThat(registry.find("qnop.scheduler.last.run.timestamp").gauges())
        .hasSize(SchedulerJobCatalog.jobIds().size());
  }

  @Test
  @DisplayName("flags a job whose last run failed")
  void flagsAFailedRun() {
    bind();

    assertThat(gauge("qnop.scheduler.last.run.failed", RAN_BADLY)).isEqualTo(1.0);
    assertThat(gauge("qnop.scheduler.last.run.failed", RAN_OK)).isZero();
  }

  @Test
  @DisplayName("reports NaN, not zero, for a job that has never run")
  void neverRunIsNotANumber() {
    bind();

    assertThat(gauge("qnop.scheduler.last.run.timestamp", NEVER_RAN)).isNaN();
    assertThat(gauge("qnop.scheduler.last.run.failed", NEVER_RAN)).isNaN();
  }

  @Test
  @DisplayName("reads the scheduler table once for all gauges of a scrape")
  void oneScrapeReadsTheTableOnce() {
    bind();

    for (String jobId : SchedulerJobCatalog.jobIds()) {
      registry.find("qnop.scheduler.last.run.timestamp").tag("job", jobId).gauge().value();
      registry.find("qnop.scheduler.last.run.failed").tag("job", jobId).gauge().value();
    }

    verify(scheduler, times(1)).list();
  }
}
