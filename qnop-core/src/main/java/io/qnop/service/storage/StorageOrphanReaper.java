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
package io.qnop.service.storage;

import io.qnop.service.storage.StorageConsistencyService.ConsistencyReport;
import io.qnop.service.storage.StorageConsistencyService.OrphanView;
import io.qnop.service.storage.StorageRemediationService.RemediationResult;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled bucket-wide orphan reaper (issue #523, ADR-0044) — the automated complement to the
 * admin dashboard. Where the existing {@code StorageService} reaper only deletes uploaded-but-
 * uncommitted staging rows it can see in the registry, this one scans the whole bucket and reaps
 * <em>committed-namespace</em> orphans that no database row references at all (e.g. a version
 * delete whose object delete failed).
 *
 * <p>Guarded for safety: it is opt-in ({@code orphanReaperEnabled}, default off), <b>dry-run by
 * default</b> (logs instead of deletes), only touches orphans older than a generous grace period
 * (well above the longest plausible ingest, so an in-flight upload can never look like an orphan),
 * bounds deletions per tick, holds a ShedLock (ADR-0029) so only one instance runs, and deletes
 * through the same in-transaction re-check path as the dashboard.
 */
@Component
public class StorageOrphanReaper {

  private static final Logger log = LoggerFactory.getLogger(StorageOrphanReaper.class);

  private final StorageConsistencyService scanner;
  private final StorageRemediationService remediation;
  private final S3Properties properties;

  public StorageOrphanReaper(
      StorageConsistencyService scanner,
      StorageRemediationService remediation,
      S3Properties properties) {
    this.scanner = scanner;
    this.remediation = remediation;
    this.properties = properties;
  }

  @Scheduled(cron = "${qnop.s3.orphan-reaper-cron:0 15 4 * * *}")
  @SchedulerLock(name = "storageBucketOrphanReaper", lockAtMostFor = "PT30M")
  public void reap() {
    if (!properties.orphanReaperEnabled()) {
      return;
    }
    ConsistencyReport report = scanner.scan();
    Instant cutoff = Instant.now().minus(properties.orphanReaperGracePeriod());
    List<String> eligible =
        report.orphaned().stream()
            .filter(
                orphan -> orphan.lastModified() != null && orphan.lastModified().isBefore(cutoff))
            .limit(properties.orphanReaperMaxDeletes())
            .map(OrphanView::storageKey)
            .toList();

    if (eligible.isEmpty()) {
      log.info(
          "Storage bucket reaper found no eligible orphans (orphans={}, grace={}).",
          report.orphaned().size(),
          properties.orphanReaperGracePeriod());
      return;
    }

    if (properties.orphanReaperDryRun()) {
      log.info(
          "Storage bucket reaper [dry-run]: would delete {} orphan(s) older than {}; enable by "
              + "setting qnop.s3.orphan-reaper-dry-run=false.",
          eligible.size(),
          properties.orphanReaperGracePeriod());
      return;
    }

    // A null actor marks the deletion as system-driven in the audit trail.
    RemediationResult result = remediation.deleteOrphans(eligible, null);
    log.info(
        "Storage bucket reaper deleted {} orphan(s), skipped {} (re-referenced since scan).",
        result.deleted().size(),
        result.skipped().size());
  }
}
