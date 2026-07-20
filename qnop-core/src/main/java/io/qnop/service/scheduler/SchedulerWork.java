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
 * scheduler owns, so implementations do their raw repository work and need no transaction of their
 * own. The {@code dryRun} flag is honoured only by dry-run-capable jobs (the storage reaper); the
 * token sweeps ignore it.
 */
@FunctionalInterface
public interface SchedulerWork {
  void run(boolean dryRun);
}
