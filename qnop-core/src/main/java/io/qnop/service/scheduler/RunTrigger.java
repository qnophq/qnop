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
 * What caused a maintenance job to run (issue #524, ADR-0045). {@link #SCHEDULED} is the cron tick,
 * which honours the job's {@code enabled} flag; {@link #MANUAL} is an admin's run-now, which is an
 * explicit override and runs regardless of {@code enabled}. Only {@code MANUAL} runs are written to
 * the SYSTEM audit stream (ADR-0043) — scheduled runs would flood the trail; their outcome lives on
 * the {@code scheduler_job} row.
 */
public enum RunTrigger {
  SCHEDULED,
  MANUAL
}
