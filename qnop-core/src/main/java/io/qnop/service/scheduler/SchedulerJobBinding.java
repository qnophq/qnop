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

import io.qnop.service.RefreshTokenService;
import io.qnop.service.TokenRevocationService;
import io.qnop.service.auth.EmailVerificationTokenService;
import io.qnop.service.auth.PasswordResetTokenService;
import io.qnop.service.review.ReviewArchiveService;
import io.qnop.service.review.ReviewPurgeService;
import io.qnop.service.storage.StorageService;
import org.springframework.stereotype.Component;

/**
 * Binds each catalogued job id to the runnable that performs it (issue #524, ADR-0045).
 * Registration lives here — one wiring point — rather than in each sweep service, so {@link
 * SchedulerService} depends on no service (no dependency cycle) and the owning services stay free
 * of scheduler plumbing. Constructor-time registration completes before any cron tick or run-now
 * can fire.
 *
 * <p>The token sweeps ignore the dry-run flag; the storage reaper, the review archive and the
 * review purge honour it. The purge is also the one {@code selfTransactional} job (issue #577) —
 * the gate hands it no transaction, and it opens one per review itself.
 */
@Component
public class SchedulerJobBinding {

  public SchedulerJobBinding(
      SchedulerService scheduler,
      EmailVerificationTokenService emailVerificationTokens,
      PasswordResetTokenService passwordResetTokens,
      RefreshTokenService refreshTokens,
      TokenRevocationService revokedTokens,
      StorageService storage,
      ReviewArchiveService reviewArchive,
      ReviewPurgeService reviewPurge) {
    scheduler.register(
        SchedulerJobCatalog.EMAIL_VERIFICATION_TOKEN_SWEEP,
        dryRun -> {
          emailVerificationTokens.sweepOnce();
          return null;
        });
    scheduler.register(
        SchedulerJobCatalog.PASSWORD_RESET_TOKEN_SWEEP,
        dryRun -> {
          passwordResetTokens.sweepOnce();
          return null;
        });
    scheduler.register(
        SchedulerJobCatalog.REFRESH_TOKEN_SWEEP,
        dryRun -> {
          refreshTokens.sweepExpiredOnce();
          return null;
        });
    scheduler.register(
        SchedulerJobCatalog.REVOKED_TOKEN_SWEEP,
        dryRun -> {
          revokedTokens.sweepExpiredOnce();
          return null;
        });
    scheduler.register(SchedulerJobCatalog.STORAGE_ORPHAN_REAPER, storage::reapOrphansOnce);
    scheduler.register(SchedulerJobCatalog.REVIEW_ARCHIVE, reviewArchive::archiveOnce);
    scheduler.register(SchedulerJobCatalog.REVIEW_PURGE, reviewPurge::purgeOnce);
  }
}
