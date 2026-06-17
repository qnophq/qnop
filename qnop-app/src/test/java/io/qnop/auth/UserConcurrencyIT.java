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
package io.qnop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the User concurrency control (issue #61, ADR-0030): {@code @Version} guards full-entity
 * edits, and the atomic revocation update bumps the version so a stale write cannot revert it. Runs
 * against a real PostgreSQL (Testcontainers); each test rolls back. Requires Docker.
 */
@Transactional
class UserConcurrencyIT extends AbstractIntegrationTest {

  @Autowired UserRepository users;
  @PersistenceContext EntityManager em;

  private User newUser() {
    return users.saveAndFlush(
        User.internal(
            "Conc", "conc-" + UUID.randomUUID() + "@e.com", "u-" + UUID.randomUUID(), "h"));
  }

  @Test
  void versionStartsAtZeroAndIncrementsOnFullEntitySave() {
    User saved = newUser();
    assertThat(saved.getVersion()).isZero();

    saved.setDisplayName("renamed");
    assertThat(users.saveAndFlush(saved).getVersion()).isEqualTo(1L);
  }

  @Test
  void atomicRevocationBumpsVersionAndCannotBeRevertedByAStaleSave() {
    UUID id = newUser().getId();
    User stale = users.findById(id).orElseThrow(); // version 0, password_invalidated_before null
    em.detach(stale);

    // A concurrent revocation: atomic, version-bumping.
    Instant when = Instant.now();
    users.bumpPasswordInvalidatedBefore(id, when);

    // The stale full-entity save must now fail rather than reverting the revocation.
    stale.setDisplayName("changed");
    assertThatThrownBy(() -> users.saveAndFlush(stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    User fresh = users.findById(id).orElseThrow();
    assertThat(fresh.getPasswordInvalidatedBefore()).isNotNull();
    assertThat(fresh.getVersion()).isEqualTo(1L);
  }

  @Test
  void touchLastLoginUpdatesTheTimestampAtomically() {
    UUID id = newUser().getId();
    Instant when = Instant.now();

    users.touchLastLogin(id, when);

    assertThat(users.findById(id).orElseThrow().getLastLoginAt()).isNotNull();
  }
}
