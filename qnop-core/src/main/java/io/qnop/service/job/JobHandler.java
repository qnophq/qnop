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
package io.qnop.service.job;

/**
 * Processes one job type in the durable queue (ADR-0033). Implementations are Spring beans; {@link
 * JobService} dispatches by {@link #type()}. Consumers (ingest extraction #245, re-anchoring #248,
 * diff #249) implement this.
 */
public interface JobHandler {

  /**
   * The job type this handler processes, matching {@code Job.type} (e.g. {@code
   * "document.extraction"}).
   */
  String type();

  /**
   * Processes the job. **Must be idempotent** — a job may run more than once (a crash after side
   * effects but before commit re-runs it). Throwing signals a failure, which the queue retries with
   * capped backoff and eventually marks {@code FAILED}.
   */
  void handle(String payload);
}
