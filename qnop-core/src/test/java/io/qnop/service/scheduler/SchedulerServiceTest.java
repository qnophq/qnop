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
package io.qnop.service.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.AuditEvent;
import io.qnop.entity.SchedulerJob;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.SchedulerJobRepository;
import io.qnop.service.limits.FeatureDisabledException;
import io.qnop.service.limits.FeatureToggleProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
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
 * DB-free unit tests for the scheduler gate (issue #524, ADR-0045): the enabled/disabled semantics,
 * fail-open, dry-run capability guard, manual-run locking, and the SYSTEM audit writes. A mock
 * {@link PlatformTransactionManager} runs each {@code TransactionTemplate} callback in-line, so the
 * gate's logic is exercised without a real database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerServiceTest {

  private static final String TOKEN_JOB = SchedulerJobCatalog.REFRESH_TOKEN_SWEEP;
  private static final String REAPER_JOB = SchedulerJobCatalog.STORAGE_ORPHAN_REAPER;
  private static final String PURGE_JOB = SchedulerJobCatalog.REVIEW_PURGE;

  @Mock private SchedulerJobRepository jobs;
  @Mock private AuditEventRepository auditEvents;
  @Mock private LockProvider lockProvider;
  @Mock private PlatformTransactionManager transactionManager;

  private SchedulerService scheduler;

  @BeforeEach
  void setUp() {
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    scheduler =
        new SchedulerService(
            jobs, auditEvents, lockProvider, transactionManager, FeatureToggleProperties.all());
  }

  @Test
  @DisplayName(
      "a scheduled run of an enabled job runs the work and records SUCCESS without auditing")
  void scheduledRunEnabled() {
    SchedulerJob job = SchedulerJob.seed(TOKEN_JOB, true);
    when(jobs.findById(TOKEN_JOB)).thenReturn(Optional.of(job));
    AtomicInteger runs = new AtomicInteger();
    scheduler.register(
        TOKEN_JOB,
        dryRun -> {
          runs.incrementAndGet();
          return null;
        });

    RunOutcome outcome = scheduler.runScheduled(TOKEN_JOB);

    assertThat(outcome).isEqualTo(RunOutcome.SUCCESS);
    assertThat(runs).hasValue(1);
    assertThat(job.getLastOutcome()).isEqualTo("SUCCESS");
    assertThat(job.getLastTrigger()).isEqualTo("SCHEDULED");
    verify(jobs).save(job);
    verify(auditEvents, never()).save(any()); // scheduled runs never audit
  }

  @Test
  @DisplayName("a scheduled run of a disabled job is skipped: no work, no record")
  void scheduledRunDisabled() {
    SchedulerJob job = SchedulerJob.seed(TOKEN_JOB, true);
    job.updateSettings(false, null);
    when(jobs.findById(TOKEN_JOB)).thenReturn(Optional.of(job));
    AtomicInteger runs = new AtomicInteger();
    scheduler.register(
        TOKEN_JOB,
        dryRun -> {
          runs.incrementAndGet();
          return null;
        });

    RunOutcome outcome = scheduler.runScheduled(TOKEN_JOB);

    assertThat(outcome).isEqualTo(RunOutcome.SKIPPED_DISABLED);
    assertThat(runs).hasValue(0);
    verify(jobs, never()).save(any());
  }

  @Test
  @DisplayName("the work's summary is recorded as the run's detail (#577 follow-up)")
  void workSummaryBecomesLastDetail() {
    SchedulerJob job = SchedulerJob.seed(TOKEN_JOB, true);
    when(jobs.findById(TOKEN_JOB)).thenReturn(Optional.of(job));
    scheduler.register(TOKEN_JOB, dryRun -> "Purged 2 review(s) and 1 storage object(s)");

    RunOutcome outcome = scheduler.runScheduled(TOKEN_JOB);

    assertThat(outcome).isEqualTo(RunOutcome.SUCCESS);
    assertThat(job.getLastDetail()).isEqualTo("Purged 2 review(s) and 1 storage object(s)");
  }

  @Test
  @DisplayName("a summary-less dry run still leaves a marker as the detail")
  void summarylessDryRunLeavesMarker() {
    SchedulerJob job = SchedulerJob.seed(REAPER_JOB, true);
    job.updateSettings(null, true);
    when(jobs.findById(REAPER_JOB)).thenReturn(Optional.of(job));
    scheduler.register(REAPER_JOB, dryRun -> null);

    scheduler.runScheduled(REAPER_JOB);

    assertThat(job.getLastDetail()).isEqualTo("Dry run");
  }

  @Test
  @DisplayName("a failing work records FAILURE and returns FAILURE without throwing")
  void workFailureIsRecordedNotThrown() {
    SchedulerJob job = SchedulerJob.seed(TOKEN_JOB, true);
    when(jobs.findById(TOKEN_JOB)).thenReturn(Optional.of(job));
    scheduler.register(
        TOKEN_JOB,
        dryRun -> {
          throw new IllegalStateException("boom");
        });

    RunOutcome outcome = scheduler.runScheduled(TOKEN_JOB);

    assertThat(outcome).isEqualTo(RunOutcome.FAILURE);
    assertThat(job.getLastOutcome()).isEqualTo("FAILURE");
    assertThat(job.getLastDetail()).contains("boom");
  }

  @Test
  @DisplayName("the state read failing open still runs the job")
  void failsOpenWhenStateUnreadable() {
    when(jobs.findById(TOKEN_JOB))
        .thenThrow(new RuntimeException("db down"))
        .thenReturn(Optional.empty()); // recordOutcome's own read
    AtomicInteger runs = new AtomicInteger();
    scheduler.register(
        TOKEN_JOB,
        dryRun -> {
          runs.incrementAndGet();
          return null;
        });

    RunOutcome outcome = scheduler.runScheduled(TOKEN_JOB);

    assertThat(outcome).isEqualTo(RunOutcome.SUCCESS);
    assertThat(runs).hasValue(1);
  }

  @Test
  @DisplayName("run-now runs a disabled job (explicit override), audits, and unlocks")
  void runNowOverridesDisabledAndAudits() {
    SchedulerJob job = SchedulerJob.seed(TOKEN_JOB, true);
    job.updateSettings(false, null);
    when(jobs.findById(TOKEN_JOB)).thenReturn(Optional.of(job));
    SimpleLock lock = org.mockito.Mockito.mock(SimpleLock.class);
    when(lockProvider.lock(any())).thenReturn(Optional.of(lock));
    AtomicInteger runs = new AtomicInteger();
    scheduler.register(
        TOKEN_JOB,
        dryRun -> {
          runs.incrementAndGet();
          return null;
        });
    UUID actor = UUID.randomUUID();

    SchedulerService.SchedulerJobView view = scheduler.runNow(actor, TOKEN_JOB);

    assertThat(runs).hasValue(1);
    assertThat(view.lastOutcome()).isEqualTo("SUCCESS");
    verify(lock).unlock();
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(captor.capture());
    assertThat(captor.getValue().getActorId()).isEqualTo(actor);
    assertThat(captor.getValue().getEventType()).isEqualTo("scheduler.job.run");
  }

  @Test
  @DisplayName("run-now returns 409 semantics when the lock is already held")
  void runNowBusy() {
    when(lockProvider.lock(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> scheduler.runNow(UUID.randomUUID(), TOKEN_JOB))
        .isInstanceOf(SchedulerJobBusyException.class);
  }

  @Test
  @DisplayName("an unknown job id is a not-found error")
  void unknownJobIsNotFound() {
    assertThatThrownBy(() -> scheduler.runNow(UUID.randomUUID(), "nope"))
        .isInstanceOf(SchedulerJobNotFoundException.class);
    assertThatThrownBy(() -> scheduler.updateSettings(UUID.randomUUID(), "nope", true, null))
        .isInstanceOf(SchedulerJobNotFoundException.class);
  }

  @Test
  @DisplayName("dry-run on a non-capable job is rejected")
  void dryRunRejectedForIncapableJob() {
    assertThatThrownBy(() -> scheduler.updateSettings(UUID.randomUUID(), TOKEN_JOB, null, true))
        .isInstanceOf(DryRunNotSupportedException.class);
    verify(jobs, never()).save(any());
  }

  @Test
  @DisplayName("update persists settings and audits the change")
  void updatePersistsAndAudits() {
    SchedulerJob job = SchedulerJob.seed(REAPER_JOB, true);
    when(jobs.findById(REAPER_JOB)).thenReturn(Optional.of(job));
    when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    UUID actor = UUID.randomUUID();

    SchedulerService.SchedulerJobView view =
        scheduler.updateSettings(actor, REAPER_JOB, false, true);

    assertThat(view.enabled()).isFalse();
    assertThat(view.dryRun()).isTrue();
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(captor.capture());
    assertThat(captor.getValue().getEventType()).isEqualTo("scheduler.job.updated");
  }

  @Test
  @DisplayName("list joins the static catalogue with the persisted rows")
  void listJoinsCatalogueAndRows() {
    SchedulerJob reaper = SchedulerJob.seed(REAPER_JOB, true);
    reaper.updateSettings(false, true);
    when(jobs.findAllById(any())).thenReturn(List.of(reaper));

    List<SchedulerService.SchedulerJobView> views = scheduler.list();

    assertThat(views).hasSize(SchedulerJobCatalog.definitions().size());
    SchedulerService.SchedulerJobView reaperView =
        views.stream().filter(v -> v.jobId().equals(REAPER_JOB)).findFirst().orElseThrow();
    assertThat(reaperView.enabled()).isFalse();
    assertThat(reaperView.dryRun()).isTrue();
    assertThat(reaperView.supportsDryRun()).isTrue();
    // A job with no row defaults to its catalogued enabledByDefault, not dry-run.
    SchedulerService.SchedulerJobView tokenView =
        views.stream().filter(v -> v.jobId().equals(TOKEN_JOB)).findFirst().orElseThrow();
    assertThat(tokenView.enabled()).isTrue();
    assertThat(tokenView.dryRun()).isFalse();
    // …and a disabled-by-default job must not advertise itself as enabled (issue #577).
    SchedulerService.SchedulerJobView purgeView =
        views.stream().filter(v -> v.jobId().equals(PURGE_JOB)).findFirst().orElseThrow();
    assertThat(purgeView.enabled()).isFalse();
  }

  // --- the two catalogue flags of issue #577 -------------------------------

  @Test
  @DisplayName("a disabled-by-default job with no row is skipped, not run")
  void disabledByDefaultJobIsSkippedWhenUnseeded() {
    when(jobs.findById(PURGE_JOB)).thenReturn(Optional.empty());
    AtomicInteger runs = new AtomicInteger();
    scheduler.register(
        PURGE_JOB,
        dryRun -> {
          runs.incrementAndGet();
          return null;
        });

    RunOutcome outcome = scheduler.runScheduled(PURGE_JOB);

    // The old "no row ⇒ enabled" fallback would have armed an irreversible purge here.
    assertThat(outcome).isEqualTo(RunOutcome.SKIPPED_DISABLED);
    assertThat(runs).hasValue(0);
  }

  @Test
  @DisplayName(
      "fail-open honours the catalogued default: the purge stays off if state is unreadable")
  void failOpenRespectsDisabledByDefault() {
    when(jobs.findById(PURGE_JOB)).thenThrow(new IllegalStateException("scheduler_job unreadable"));
    AtomicInteger runs = new AtomicInteger();
    scheduler.register(
        PURGE_JOB,
        dryRun -> {
          runs.incrementAndGet();
          return null;
        });

    assertThat(scheduler.runScheduled(PURGE_JOB)).isEqualTo(RunOutcome.SKIPPED_DISABLED);
    assertThat(runs).hasValue(0);
  }

  @Test
  @DisplayName("an admin can enable a disabled-by-default job, and run-now overrides regardless")
  void disabledByDefaultCanBeEnabledAndRunNow() {
    when(jobs.findById(PURGE_JOB)).thenReturn(Optional.empty());
    when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AtomicInteger runs = new AtomicInteger();
    scheduler.register(
        PURGE_JOB,
        dryRun -> {
          runs.incrementAndGet();
          return null;
        });

    SchedulerService.SchedulerJobView enabled =
        scheduler.updateSettings(UUID.randomUUID(), PURGE_JOB, true, null);
    assertThat(enabled.enabled()).isTrue();

    when(lockProvider.lock(any())).thenReturn(Optional.of(() -> {}));
    scheduler.runNow(UUID.randomUUID(), PURGE_JOB);
    assertThat(runs).hasValue(1);
  }

  @Test
  @DisplayName("a self-transactional job runs outside the gate's transaction")
  void selfTransactionalJobIsNotWrapped() {
    when(jobs.findById(TOKEN_JOB)).thenReturn(Optional.of(SchedulerJob.seed(TOKEN_JOB, true)));
    when(jobs.findById(PURGE_JOB)).thenReturn(Optional.of(SchedulerJob.seed(PURGE_JOB, true)));
    scheduler.register(TOKEN_JOB, dryRun -> null);
    scheduler.register(PURGE_JOB, dryRun -> null);

    scheduler.runScheduled(TOKEN_JOB);
    // A wrapped job costs three transactions: read state, run work, record outcome.
    verify(transactionManager, times(3)).getTransaction(any());

    reset(transactionManager);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    scheduler.runScheduled(PURGE_JOB);
    // The purge costs two — the work itself is invoked with no enclosing transaction (issue #577),
    // so it can commit per review and delete storage objects between those commits.
    verify(transactionManager, times(2)).getTransaction(any());
  }

  @Test
  @DisplayName("a deployment may withhold run-now, and its schedules keep running")
  void manualRunCanBeWithheld() {
    SchedulerService withoutManualRun =
        new SchedulerService(
            jobs,
            auditEvents,
            lockProvider,
            transactionManager,
            FeatureToggleProperties.allExcept(
                FeatureDisabledException.Feature.SCHEDULER_MANUAL_RUN));
    AtomicInteger runs = new AtomicInteger();
    withoutManualRun.register(
        TOKEN_JOB,
        dryRun -> {
          runs.incrementAndGet();
          return null;
        });

    assertThatThrownBy(() -> withoutManualRun.runNow(UUID.randomUUID(), TOKEN_JOB))
        .isInstanceOf(FeatureDisabledException.class);

    assertThat(withoutManualRun.manualRunEnabled()).isFalse();
    // Refused before anything could happen: nothing ran, and no lock was taken
    // that a scheduled run would then have had to wait for.
    assertThat(runs).hasValue(0);
    verify(lockProvider, never()).lock(any());
    verify(auditEvents, never()).save(any());

    // The whole point of the switch: what is withheld is the manual override,
    // not the job. The cron entry point still runs it.
    when(jobs.findById(TOKEN_JOB)).thenReturn(Optional.of(SchedulerJob.seed(TOKEN_JOB, true)));

    assertThat(withoutManualRun.runScheduled(TOKEN_JOB)).isEqualTo(RunOutcome.SUCCESS);
    assertThat(runs).hasValue(1);
  }

  @Test
  @DisplayName("a deployment may fix the job settings, covering dry-run as well as enabled")
  void jobSettingsCanBeFixed() {
    SchedulerService withFixedSettings =
        new SchedulerService(
            jobs,
            auditEvents,
            lockProvider,
            transactionManager,
            FeatureToggleProperties.allExcept(
                FeatureDisabledException.Feature.SCHEDULER_JOB_SETTINGS));

    assertThatThrownBy(
            () -> withFixedSettings.updateSettings(UUID.randomUUID(), TOKEN_JOB, false, null))
        .isInstanceOf(FeatureDisabledException.class);
    // Dry-run is the same door: it is a soft off for the jobs that delete
    // things, so leaving it open would defeat the switch.
    assertThatThrownBy(
            () -> withFixedSettings.updateSettings(UUID.randomUUID(), TOKEN_JOB, null, true))
        .isInstanceOf(FeatureDisabledException.class);

    assertThat(withFixedSettings.jobSettingsEditable()).isFalse();
    // Nothing was written or audited on the way to the refusal.
    verify(jobs, never()).save(any());
    verify(auditEvents, never()).save(any());
  }
}
