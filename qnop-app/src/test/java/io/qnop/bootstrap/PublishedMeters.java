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
 * Registers every meter qnop publishes on a registry, each with one sample (issue #962), so a test
 * can look at the whole catalogue the way a scrape would without booting the application.
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
    jobs.executed("document.extract", JobMeters.Outcome.SUCCESS, ONCE);
    jobs.waited("document.extract", ONCE);
    jobs.reaped(1);

    IngestMeters ingest = new IngestMeters(registry);
    ingest.extracted(IngestMeters.Format.PDF, IngestMeters.Outcome.SUCCESS, ONCE);
    ingest.uploaded(IngestMeters.Format.PDF, 1);
    ingest.surfaces(IngestMeters.Format.PDF, 1);

    ConversionMeters conversion = new ConversionMeters(registry);
    conversion.converted(ConversionMeters.Outcome.SUCCESS, ONCE);
    conversion.refused(ONCE);

    new ReanchorMeters(registry).reanchored(1, 1, 1);

    new SchedulerMeters(registry)
        .ran(
            SchedulerJobCatalog.jobIds().iterator().next(),
            SchedulerMeters.Outcome.SUCCESS,
            SchedulerMeters.Trigger.SCHEDULED,
            false,
            ONCE);

    StorageMeters storage = new StorageMeters(registry);
    storage.called(StorageMeters.Operation.PUT, StorageMeters.Outcome.SUCCESS, ONCE);
    storage.transferred(StorageMeters.Direction.UPLOAD, 1);
    storage.orphansReaped(1);

    new MailMeters(registry).sent(MailTemplateKey.PASSWORD_RESET, MailMeters.Outcome.SENT, ONCE);

    AuthMeters auth = new AuthMeters(registry);
    auth.login(AuthMeters.Method.LOCAL, AuthMeters.LoginOutcome.SUCCESS);
    auth.refresh(AuthMeters.RefreshOutcome.SUCCESS);
    auth.rateLimited("login");

    ReviewEventMeters events = new ReviewEventMeters(registry);
    events.committed(ReviewEventMeters.Type.COMMENT_ADDED);
    events.listenerFailed(ReviewEventMeters.Type.COMMENT_ADDED);
    events.dropped();

    new TrackingMeters(registry)
        .forwarded(TrackingProvider.MATOMO, TrackingMeters.Outcome.SUCCESS, ONCE);

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
