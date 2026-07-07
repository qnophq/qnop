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
package io.qnop.service.diff;

import io.qnop.entity.VersionDiff;
import io.qnop.repository.VersionDiffRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a computed diff in its <em>own</em> transaction so a concurrent first-writer's
 * unique-pair race is contained (issue #351). A separate bean is required for two reasons: the
 * {@code REQUIRES_NEW} boundary only applies through a Spring proxy (a self-invoked annotated
 * method would be ignored), and — critically — a unique-constraint violation on flush marks its
 * transaction rollback-only, so the failing insert must sit in a transaction the caller does not
 * share. The violation propagates out of {@link #store} (its own transaction rolled back cleanly);
 * {@link VersionDiffService} swallows it, since the winning row's payload is identical (versions
 * are immutable, ADR-0034).
 */
@Component
class VersionDiffCacheWriter {

  private final VersionDiffRepository diffs;

  VersionDiffCacheWriter(VersionDiffRepository diffs) {
    this.diffs = diffs;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void store(VersionDiff diff) {
    // saveAndFlush, not save: force the INSERT (and thus any unique-pair violation) now, inside
    // this REQUIRES_NEW transaction, rather than deferring it to the outer commit — the id is
    // application-assigned (UUIDv7), so a plain save would not flush until commit.
    diffs.saveAndFlush(diff);
  }
}
