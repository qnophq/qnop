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

import java.util.List;
import java.util.Optional;

/**
 * The fixed catalogue of maintenance sweeps the scheduler dashboard surfaces (issue #524,
 * ADR-0045). The set is closed and lives in code: each {@link SchedulerJobDefinition} names a job,
 * its cron, and whether it is dry-run-capable. The two internal poller/reaper jobs of the durable
 * job queue (ADR-0033) are deliberately excluded — they are engine internals, not operator-facing
 * maintenance.
 *
 * <p>The {@code jobId} constants are compile-time-constant strings so they double as the
 * {@code @SchedulerLock} names on the owning service methods — one source of truth, no drift.
 */
public final class SchedulerJobCatalog {

  public static final String EMAIL_VERIFICATION_TOKEN_SWEEP = "emailVerificationTokenSweep";
  public static final String PASSWORD_RESET_TOKEN_SWEEP = "passwordResetTokenSweep";
  public static final String REFRESH_TOKEN_SWEEP = "refreshTokenSweep";
  public static final String REVOKED_TOKEN_SWEEP = "revokedTokenSweep";
  public static final String STORAGE_ORPHAN_REAPER = "storageOrphanReaper";

  private static final List<SchedulerJobDefinition> DEFINITIONS =
      List.of(
          new SchedulerJobDefinition(
              EMAIL_VERIFICATION_TOKEN_SWEEP,
              "Email-verification token sweep",
              "Purges expired e-mail verification tokens so the table does not grow unbounded.",
              "0 30 3 * * *",
              false),
          new SchedulerJobDefinition(
              PASSWORD_RESET_TOKEN_SWEEP,
              "Password-reset token sweep",
              "Purges expired password-reset tokens once they can no longer be redeemed.",
              "0 35 3 * * *",
              false),
          new SchedulerJobDefinition(
              REFRESH_TOKEN_SWEEP,
              "Refresh token sweep",
              "Deletes expired refresh tokens; reuse-detection is moot once a token has expired.",
              "0 40 3 * * *",
              false),
          new SchedulerJobDefinition(
              REVOKED_TOKEN_SWEEP,
              "Revoked access-token sweep",
              "Drops revoked access-token denylist rows once their own expiry has passed.",
              "0 45 3 * * *",
              false),
          new SchedulerJobDefinition(
              STORAGE_ORPHAN_REAPER,
              "Storage orphan reaper",
              "Deletes uploaded-but-uncommitted storage objects older than the grace period.",
              "0 30 3 * * *",
              true));

  private static final List<String> IDS =
      DEFINITIONS.stream().map(SchedulerJobDefinition::jobId).toList();

  private SchedulerJobCatalog() {}

  /** All catalogued jobs, in a stable dashboard order. */
  public static List<SchedulerJobDefinition> definitions() {
    return DEFINITIONS;
  }

  /** The catalogued job ids, in the same stable order. */
  public static List<String> jobIds() {
    return IDS;
  }

  /** The definition for a job id, if it is catalogued. */
  public static Optional<SchedulerJobDefinition> find(String jobId) {
    return DEFINITIONS.stream().filter(definition -> definition.jobId().equals(jobId)).findFirst();
  }
}
