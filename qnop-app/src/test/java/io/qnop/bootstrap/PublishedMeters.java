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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.qnop.observability.metrics.AuthMeters;
import io.qnop.observability.metrics.ConversionMeters;
import io.qnop.observability.metrics.IngestMeters;
import io.qnop.observability.metrics.JobMeters;
import io.qnop.observability.metrics.MailMeters;
import io.qnop.observability.metrics.ReanchorMeters;
import io.qnop.observability.metrics.ReviewEventMeters;
import io.qnop.observability.metrics.SchedulerMeters;
import io.qnop.observability.metrics.StorageMeters;
import io.qnop.observability.metrics.TrackingMeters;
import io.qnop.service.audit.AuditChainVerificationService;
import io.qnop.service.convert.ThrottledOfficeConverter;
import io.qnop.service.job.JobService;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import io.qnop.service.stats.ProductStatsService;
import io.qnop.service.tracking.TrackingProvider;
import io.qnop.web.RunningVersion;
import java.time.Duration;

/**
 * Registers every meter qnop publishes on a registry, with one sample for every value of every enum
 * tag (issue #962), so a test can look at the whole catalogue, labels included, the way a scrape
 * would without booting the application.
 *
 * <p>Most timers and counters only exist once something has happened; a scrape of an idle
 * application does not show them. This is the one place that knows them all: a new meter class or
 * gauge binder is added here, and the catalogue, dashboard and cardinality tests then hold it to
 * the same rules as the rest.
 */
final class PublishedMeters {

  private static final Duration ONCE = Duration.ofMillis(1);

  private PublishedMeters() {}

  static void registerAllOn(MeterRegistry registry) {
    JobMeters jobs = new JobMeters(registry);
    for (JobMeters.Outcome outcome : JobMeters.Outcome.values()) {
      jobs.executed("document.extract", outcome, ONCE);
    }
    jobs.waited("document.extract", ONCE);
    jobs.reaped(1);

    IngestMeters ingest = new IngestMeters(registry);
    for (IngestMeters.Format format : IngestMeters.Format.values()) {
      for (IngestMeters.Outcome outcome : IngestMeters.Outcome.values()) {
        ingest.extracted(format, outcome, ONCE);
      }
      ingest.uploaded(format, 1);
      ingest.surfaces(format, 1);
    }

    ConversionMeters conversion = new ConversionMeters(registry);
    for (ConversionMeters.Outcome outcome : ConversionMeters.Outcome.values()) {
      conversion.converted(outcome, ONCE);
    }
    conversion.refused(ONCE);

    new ReanchorMeters(registry).reanchored(1, 1, 1);

    SchedulerMeters scheduler = new SchedulerMeters(registry);
    String job = SchedulerJobCatalog.jobIds().iterator().next();
    for (SchedulerMeters.Outcome outcome : SchedulerMeters.Outcome.values()) {
      for (SchedulerMeters.Trigger trigger : SchedulerMeters.Trigger.values()) {
        scheduler.ran(job, outcome, trigger, false, ONCE);
        scheduler.ran(job, outcome, trigger, true, ONCE);
      }
    }

    StorageMeters storage = new StorageMeters(registry);
    for (StorageMeters.Operation operation : StorageMeters.Operation.values()) {
      for (StorageMeters.Outcome outcome : StorageMeters.Outcome.values()) {
        storage.called(operation, outcome, ONCE);
      }
    }
    for (StorageMeters.Direction direction : StorageMeters.Direction.values()) {
      storage.transferred(direction, 1);
    }
    storage.orphansReaped(1);

    MailMeters mail = new MailMeters(registry);
    for (MailMeters.Outcome outcome : MailMeters.Outcome.values()) {
      mail.sent(MailTemplateKey.PASSWORD_RESET, outcome, ONCE);
      mail.sent(null, outcome, ONCE);
    }

    AuthMeters auth = new AuthMeters(registry);
    for (AuthMeters.Method method : AuthMeters.Method.values()) {
      for (AuthMeters.LoginOutcome outcome : AuthMeters.LoginOutcome.values()) {
        auth.login(method, outcome);
      }
    }
    for (AuthMeters.RefreshOutcome outcome : AuthMeters.RefreshOutcome.values()) {
      auth.refresh(outcome);
    }
    auth.rateLimited("login");

    ReviewEventMeters events = new ReviewEventMeters(registry);
    for (ReviewEventMeters.Type type : ReviewEventMeters.Type.values()) {
      events.committed(type);
      events.listenerFailed(type);
    }
    events.dropped();

    TrackingMeters tracking = new TrackingMeters(registry);
    for (TrackingProvider provider : TrackingProvider.values()) {
      for (TrackingMeters.Outcome outcome : TrackingMeters.Outcome.values()) {
        tracking.forwarded(provider, outcome, ONCE);
      }
    }

    new JobQueueMetrics(mock(JobService.class)).bindTo(registry);
    new SchedulerRunMetrics(mock(SchedulerService.class)).bindTo(registry);
    new ProductMetrics(mock(ProductStatsService.class)).bindTo(registry);
    RunningVersion version = mock(RunningVersion.class);
    when(version.value()).thenReturn("0.0.0");
    new BuildInfoMetrics(version).bindTo(registry);
    new AuditChainMetrics(mock(AuditChainVerificationService.class)).bindTo(registry);
    new OfficeConversionMetrics(mock(ThrottledOfficeConverter.class)).bindTo(registry);
  }
}
