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

/**
 * The unit of work a maintenance job performs, registered with {@link SchedulerService} by the
 * owning service (issue #524, ADR-0045). It is called <em>inside</em> a fresh transaction the
 * scheduler owns (unless the job is self-transactional, issue #577), so implementations do their
 * raw repository work and need no transaction of their own. The {@code dryRun} flag is honoured
 * only by dry-run-capable jobs (the storage reaper, the review archive and the review purge); the
 * token sweeps ignore it.
 */
@FunctionalInterface
public interface SchedulerWork {

  /**
   * Performs one run and returns a short human-readable summary of what happened (or, under {@code
   * dryRun}, what would have happened) — e.g. {@code "Purged 2 review(s) and 1 storage object(s)"}.
   * The scheduler records it as the job's last-run detail, surfaced on the dashboard (issue #577
   * follow-up). {@code null} means the job has nothing to report.
   */
  String run(boolean dryRun);
}
