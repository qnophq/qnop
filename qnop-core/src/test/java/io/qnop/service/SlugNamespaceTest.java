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
package io.qnop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The flat user⟷team slug namespace (issue #595, ADR-0048). */
@ExtendWith(MockitoExtension.class)
class SlugNamespaceTest {

  @Mock private UserRepository users;
  @Mock private TeamRepository teams;

  @Test
  @DisplayName("a candidate is free only when neither a user nor a team claims it")
  void freeOnlyWhenBothNamespacesAreFree() {
    SlugNamespace namespace = new SlugNamespace(users, teams);
    when(users.existsBySlugIgnoreCase("design")).thenReturn(false);
    when(teams.existsBySlugIgnoreCase("design")).thenReturn(false);

    assertThat(namespace.lockAndCheckFree("design")).isTrue();
  }

  @Test
  @DisplayName("a user slug blocks the candidate")
  void userSlugBlocks() {
    SlugNamespace namespace = new SlugNamespace(users, teams);
    when(users.existsBySlugIgnoreCase("design")).thenReturn(true);

    assertThat(namespace.lockAndCheckFree("design")).isFalse();
  }

  @Test
  @DisplayName("a team slug blocks the candidate")
  void teamSlugBlocks() {
    SlugNamespace namespace = new SlugNamespace(users, teams);
    when(users.existsBySlugIgnoreCase("design")).thenReturn(false);
    when(teams.existsBySlugIgnoreCase("design")).thenReturn(true);

    assertThat(namespace.lockAndCheckFree("design")).isFalse();
  }

  @Test
  @DisplayName("takes the advisory lock on the lowercased candidate BEFORE probing")
  void locksBeforeProbing() {
    SlugNamespace namespace = new SlugNamespace(users, teams);
    when(users.existsBySlugIgnoreCase("Design")).thenReturn(false);
    when(teams.existsBySlugIgnoreCase("Design")).thenReturn(false);

    namespace.lockAndCheckFree("Design");

    // Probing before locking would re-open the race the lock exists to close.
    InOrder order = inOrder(users, teams);
    order.verify(users).acquireSlugAllocationLock("design");
    order.verify(users).existsBySlugIgnoreCase("Design");
    order.verify(teams).existsBySlugIgnoreCase("Design");
  }
}
