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

import io.qnop.entity.AuditEvent;
import io.qnop.entity.SchedulerJob;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.SchedulerJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator gate in front of the scheduled maintenance sweeps (issue #524, ADR-0045).
 *
 * <p>Every sweep routes through {@link #runScheduled(String)} on its cron tick; the dashboard
 * drives {@link #updateSettings} (enable/disable, dry-run) and {@link #runNow} (an explicit manual
 * override). The gate reads the operator state from the {@code scheduler_job} row, records each
 * real run's outcome back onto that row, and — for manual runs and setting changes only — writes to
 * the SYSTEM audit stream (ADR-0043). Scheduled runs deliberately do not audit: five rows a day
 * forever would drown the trail, and their outcome already lives on the row.
 *
 * <h2>Transaction discipline (the architecture guardrail)</h2>
 *
 * <p>The gate is intentionally <em>not</em> a {@code @Transactional} bean. It owns transactions
 * programmatically through a {@link TransactionTemplate}, which lets it run three independent units
 * per job: read the state, run the work, then record the outcome. Because the outcome is recorded
 * in a <em>separate</em> transaction from the work, a work rollback can never erase the failure
 * record — and no Spring self-invocation caveat applies, since the work {@link SchedulerWork}
 * runnable is supplied by the owning service and invoked inside the gate's own transaction.
 *
 * <h2>Fail-open</h2>
 *
 * <p>If the state read fails (a broken {@code scheduler_job} table, a transient DB error), the gate
 * runs the job anyway. Maintenance sweeps keep the system healthy; a stuck control table must never
 * silently stop them.
 */
@Service
public class SchedulerService {

  private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

  /** Audit event types on the SYSTEM stream. */
  static final String AUDIT_RUN = "scheduler.job.run";

  static final String AUDIT_UPDATED = "scheduler.job.updated";

  /** Cap on how long a manual run may hold its lock before ShedLock reclaims it defensively. */
  private static final Duration MANUAL_LOCK_AT_MOST = Duration.ofMinutes(10);

  private static final int MAX_DETAIL_LENGTH = 512;

  private final SchedulerJobRepository jobs;
  private final AuditEventRepository auditEvents;
  private final LockProvider lockProvider;
  private final TransactionTemplate tx;
  private final Map<String, SchedulerWork> works = new ConcurrentHashMap<>();

  public SchedulerService(
      SchedulerJobRepository jobs,
      AuditEventRepository auditEvents,
      LockProvider lockProvider,
      PlatformTransactionManager transactionManager) {
    this.jobs = jobs;
    this.auditEvents = auditEvents;
    this.lockProvider = lockProvider;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** One dashboard row: the static catalogue metadata joined with the mutable operator state. */
  public record SchedulerJobView(
      String jobId,
      String displayName,
      String description,
      String cron,
      boolean supportsDryRun,
      boolean enabled,
      boolean dryRun,
      Instant lastRunAt,
      String lastOutcome,
      String lastTrigger,
      String lastDetail) {}

  /**
   * Registers the unit of work for a job; called once at start-up by {@code SchedulerJobBinding}.
   * The gate never invokes a job it has no runnable for.
   */
  public void register(String jobId, SchedulerWork work) {
    works.put(jobId, work);
  }

  /**
   * The cron entry point for a sweep: runs it honouring the {@code enabled} flag, or skips silently
   * when disabled. Called from the {@code @Scheduled}/{@code @SchedulerLock} method, so the
   * distributed lock is already held.
   */
  public RunOutcome runScheduled(String jobId) {
    return execute(jobId, RunTrigger.SCHEDULED, null);
  }

  /**
   * An admin's run-now: an explicit override that runs the job regardless of {@code enabled}, under
   * the job's distributed lock so it never overlaps a scheduled run or another manual trigger.
   *
   * @throws SchedulerJobNotFoundException if the id is not catalogued (404)
   * @throws SchedulerJobBusyException if a run is already in progress (409)
   */
  public SchedulerJobView runNow(UUID actorId, String jobId) {
    SchedulerJobDefinition definition = requireDefinition(jobId);
    LockConfiguration lockConfiguration =
        new LockConfiguration(Instant.now(), jobId, MANUAL_LOCK_AT_MOST, Duration.ZERO);
    Optional<SimpleLock> lock = lockProvider.lock(lockConfiguration);
    if (lock.isEmpty()) {
      throw new SchedulerJobBusyException(jobId);
    }
    try {
      execute(jobId, RunTrigger.MANUAL, actorId);
    } finally {
      lock.get().unlock();
    }
    return viewOf(definition, jobs.findById(jobId).orElse(null));
  }

  /**
   * Applies operator settings to a job (a null field leaves that setting unchanged), audits the
   * change, and returns the fresh view.
   *
   * @throws SchedulerJobNotFoundException if the id is not catalogued (404)
   * @throws DryRunNotSupportedException if dry-run is requested for a job that cannot dry-run (400)
   */
  public SchedulerJobView updateSettings(
      UUID actorId, String jobId, Boolean enabled, Boolean dryRun) {
    SchedulerJobDefinition definition = requireDefinition(jobId);
    if (Boolean.TRUE.equals(dryRun) && !definition.supportsDryRun()) {
      throw new DryRunNotSupportedException(jobId);
    }
    SchedulerJob saved =
        tx.execute(
            status -> {
              SchedulerJob job = jobs.findById(jobId).orElseGet(() -> SchedulerJob.seed(jobId));
              job.updateSettings(enabled, dryRun);
              SchedulerJob persisted = jobs.save(job);
              auditEvents.save(
                  AuditEvent.system(
                      AUDIT_UPDATED,
                      actorId,
                      "{\"jobId\":\""
                          + jobId
                          + "\",\"enabled\":"
                          + persisted.isEnabled()
                          + ",\"dryRun\":"
                          + persisted.isDryRun()
                          + "}"));
              return persisted;
            });
    return viewOf(definition, saved);
  }

  /** The full dashboard list: every catalogued job with its current operator state. */
  public List<SchedulerJobView> list() {
    Map<String, SchedulerJob> rows =
        tx.execute(
            status ->
                jobs.findAllById(SchedulerJobCatalog.jobIds()).stream()
                    .collect(
                        java.util.stream.Collectors.toMap(
                            SchedulerJob::getJobId, Function.identity())));
    Map<String, SchedulerJob> byId = rows == null ? Map.of() : rows;
    return SchedulerJobCatalog.definitions().stream()
        .map(definition -> viewOf(definition, byId.get(definition.jobId())))
        .toList();
  }

  // --- internals -----------------------------------------------------------

  private RunOutcome execute(String jobId, RunTrigger trigger, UUID actorId) {
    SchedulerWork work = works.get(jobId);
    if (work == null) {
      // A catalogued job with no registered runnable is a wiring bug, not a runtime input error.
      log.error("No work registered for scheduler job {}; skipping {} run", jobId, trigger);
      return RunOutcome.FAILURE;
    }
    JobState state = readState(jobId);
    if (trigger == RunTrigger.SCHEDULED && !state.enabled()) {
      log.debug("Scheduler job {} is disabled; skipping scheduled run", jobId);
      return RunOutcome.SKIPPED_DISABLED;
    }
    boolean dryRun = state.dryRun() && supportsDryRun(jobId);
    try {
      tx.executeWithoutResult(status -> work.run(dryRun));
    } catch (RuntimeException e) {
      log.error("Scheduler job {} ({}) failed", jobId, trigger, e);
      recordOutcome(jobId, RunOutcome.FAILURE, trigger, summarize(e), actorId);
      return RunOutcome.FAILURE;
    }
    recordOutcome(jobId, RunOutcome.SUCCESS, trigger, dryRun ? "dry-run" : null, actorId);
    return RunOutcome.SUCCESS;
  }

  /** Reads the enabled/dry-run state; fails open (run the job) on any read error. */
  private JobState readState(String jobId) {
    try {
      JobState state =
          tx.execute(
              status ->
                  jobs.findById(jobId)
                      .map(job -> new JobState(job.isEnabled(), job.isDryRun()))
                      .orElse(new JobState(true, false)));
      return state == null ? new JobState(true, false) : state;
    } catch (RuntimeException e) {
      log.warn("Could not read scheduler state for {}; failing open (running)", jobId, e);
      return new JobState(true, false);
    }
  }

  /**
   * Records a run's outcome on the row (best-effort, in its own transaction so a work rollback can
   * never erase it) and, for a manual run, on the SYSTEM audit stream. A failure to record is
   * logged but never masks the run itself.
   */
  private void recordOutcome(
      String jobId, RunOutcome outcome, RunTrigger trigger, String detail, UUID actorId) {
    try {
      tx.executeWithoutResult(
          status -> {
            SchedulerJob job = jobs.findById(jobId).orElseGet(() -> SchedulerJob.seed(jobId));
            job.recordRun(Instant.now(), outcome.name(), trigger.name(), detail);
            jobs.save(job);
            if (trigger == RunTrigger.MANUAL) {
              auditEvents.save(
                  AuditEvent.system(
                      AUDIT_RUN,
                      actorId,
                      "{\"jobId\":\""
                          + jobId
                          + "\",\"outcome\":\""
                          + outcome.name()
                          + "\",\"dryRun\":"
                          + "dry-run".equals(detail)
                          + "}"));
            }
          });
    } catch (RuntimeException e) {
      log.error("Failed to record outcome for scheduler job {}", jobId, e);
    }
  }

  private boolean supportsDryRun(String jobId) {
    return SchedulerJobCatalog.find(jobId)
        .map(SchedulerJobDefinition::supportsDryRun)
        .orElse(false);
  }

  private SchedulerJobDefinition requireDefinition(String jobId) {
    return SchedulerJobCatalog.find(jobId)
        .orElseThrow(() -> new SchedulerJobNotFoundException(jobId));
  }

  private static String summarize(RuntimeException e) {
    String message = e.getMessage();
    String summary =
        message == null
            ? e.getClass().getSimpleName()
            : e.getClass().getSimpleName() + ": " + message;
    return summary.length() <= MAX_DETAIL_LENGTH
        ? summary
        : summary.substring(0, MAX_DETAIL_LENGTH);
  }

  private static SchedulerJobView viewOf(SchedulerJobDefinition definition, SchedulerJob row) {
    return new SchedulerJobView(
        definition.jobId(),
        definition.displayName(),
        definition.description(),
        definition.cron(),
        definition.supportsDryRun(),
        row == null || row.isEnabled(),
        row != null && row.isDryRun(),
        row == null ? null : row.getLastRunAt(),
        row == null ? null : row.getLastOutcome(),
        row == null ? null : row.getLastTrigger(),
        row == null ? null : row.getLastDetail());
  }

  private record JobState(boolean enabled, boolean dryRun) {}
}
