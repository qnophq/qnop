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
package io.qnop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The operator-controlled state of one scheduled maintenance sweep (issue #524, ADR-0045).
 *
 * <p>The static catalogue of jobs — their display name, cron expression and dry-run capability —
 * lives in code ({@code SchedulerJobCatalog}); this row holds only what an admin can change and
 * what the last run produced: whether the scheduled tick is {@link #enabled}, whether the reaper
 * runs in {@link #dryRun} mode, and the outcome of the most recent run ({@link #lastRunAt}, {@link
 * #lastOutcome}, {@link #lastTrigger}, {@link #lastDetail}). The primary key is the natural {@code
 * jobId} — the same string used as the ShedLock name — so there is exactly one row per catalogued
 * job, seeded idempotently at start-up.
 *
 * <p>JPA runs {@code ddl-auto=none}; this mapping matches migration 0017 exactly.
 */
@Entity
@Table(name = "scheduler_job")
public class SchedulerJob {

  @Id
  @Column(name = "job_id", nullable = false, updatable = false, length = 64)
  private String jobId;

  /** Whether the scheduled tick runs; a manual run-now ignores this (explicit operator intent). */
  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  /** For a dry-run-capable job (the reaper), report-only mode: compute but do not delete. */
  @Column(name = "dry_run", nullable = false)
  private boolean dryRun;

  /** When the job last actually ran (a skipped-because-disabled tick does not touch this). */
  @Column(name = "last_run_at")
  private Instant lastRunAt;

  /**
   * Outcome of the last run — the {@code RunOutcome} name (SUCCESS / FAILURE), or null if never.
   */
  @Column(name = "last_outcome", length = 16)
  private String lastOutcome;

  /** What triggered the last run — the {@code RunTrigger} name (SCHEDULED / MANUAL), or null. */
  @Column(name = "last_trigger", length = 16)
  private String lastTrigger;

  /** Optional short detail for the last run (e.g. {@code "dry-run"} or a failure summary). */
  @Column(name = "last_detail", length = 512)
  private String lastDetail;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SchedulerJob() {
    // for JPA
  }

  private SchedulerJob(String jobId, boolean enabled, boolean dryRun) {
    this.jobId = jobId;
    this.enabled = enabled;
    this.dryRun = dryRun;
  }

  /** A freshly seeded job row: enabled, not in dry-run, never run yet. */
  public static SchedulerJob seed(String jobId) {
    return new SchedulerJob(jobId, true, false);
  }

  /** Applies operator settings; a null argument leaves that setting unchanged. */
  public void updateSettings(Boolean enabled, Boolean dryRun) {
    if (enabled != null) {
      this.enabled = enabled;
    }
    if (dryRun != null) {
      this.dryRun = dryRun;
    }
  }

  /** Records the outcome of a run that actually executed (not a disabled-skip). */
  public void recordRun(Instant at, String outcome, String trigger, String detail) {
    this.lastRunAt = at;
    this.lastOutcome = outcome;
    this.lastTrigger = trigger;
    this.lastDetail = detail;
  }

  public String getJobId() {
    return jobId;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isDryRun() {
    return dryRun;
  }

  public Instant getLastRunAt() {
    return lastRunAt;
  }

  public String getLastOutcome() {
    return lastOutcome;
  }

  public String getLastTrigger() {
    return lastTrigger;
  }

  public String getLastDetail() {
    return lastDetail;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SchedulerJob other)) {
      return false;
    }
    return jobId != null && jobId.equals(other.jobId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(jobId);
  }
}
