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
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Profile-slug allocation probes the flat namespace (issues #486, #595). */
@ExtendWith(MockitoExtension.class)
class UserSlugServiceTest {

  @Mock private SlugNamespace namespace;

  @Test
  @DisplayName("allocates the derived base when the namespace is free")
  void allocatesBase() {
    when(namespace.lockAndCheckFree("anna-krause")).thenReturn(true);

    assertThat(new UserSlugService(namespace).allocate("Anna Krause")).isEqualTo("anna-krause");
  }

  @Test
  @DisplayName("skips to base-n when the candidate is taken — by a user OR a team")
  void skipsTakenCandidates() {
    when(namespace.lockAndCheckFree("anna-krause")).thenReturn(false);
    when(namespace.lockAndCheckFree("anna-krause-2")).thenReturn(false);
    when(namespace.lockAndCheckFree("anna-krause-3")).thenReturn(true);

    assertThat(new UserSlugService(namespace).allocate("Anna Krause")).isEqualTo("anna-krause-3");
  }
}
