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
 * The static metadata for one catalogued maintenance job (issue #524, ADR-0045) — the parts that
 * live in code and never change at runtime.
 *
 * @param jobId the natural id, identical to the ShedLock name and the {@code scheduler_job} row key
 * @param displayName a human label for the dashboard
 * @param description one sentence on what the sweep does and why
 * @param cron the default cron expression the sweep fires on (informational for the dashboard)
 * @param supportsDryRun whether the job honours a report-only dry-run mode (only the reaper does)
 */
public record SchedulerJobDefinition(
    String jobId, String displayName, String description, String cron, boolean supportsDryRun) {}
