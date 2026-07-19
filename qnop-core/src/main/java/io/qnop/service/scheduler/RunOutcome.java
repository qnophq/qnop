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
 * The result of attempting to run a maintenance job (issue #524, ADR-0045). {@link #SUCCESS} and
 * {@link #FAILURE} are recorded on the {@code scheduler_job} row; {@link #SKIPPED_DISABLED} is a
 * scheduled tick that did nothing because the job is disabled — it is <em>not</em> recorded (the
 * previous run's outcome is preserved, and the disabled flag itself explains the silence).
 */
public enum RunOutcome {
  SUCCESS,
  FAILURE,
  SKIPPED_DISABLED
}
