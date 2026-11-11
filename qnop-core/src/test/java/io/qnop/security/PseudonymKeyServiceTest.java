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
package io.qnop.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PseudonymKeyServiceTest {

  private static final UUID DOC = UUID.fromString("0190a0a0-0000-7000-8000-000000000001");
  private static final UUID OTHER_DOC = UUID.fromString("0190a0a0-0000-7000-8000-000000000002");
  private static final UUID USER = UUID.fromString("0190a0a0-0000-7000-8000-0000000000aa");

  private final PseudonymKeyService service =
      PseudonymKeyService.fromSecret("a-sufficiently-long-test-secret-0123456789");

  @Test
  void isDeterministicPerDocumentAndPrincipal() {
    assertThat(service.sortKey(DOC, USER)).hasSize(32).isEqualTo(service.sortKey(DOC, USER));
  }

  @Test
  void differsPerDocument() {
    assertThat(service.sortKey(DOC, USER)).isNotEqualTo(service.sortKey(OTHER_DOC, USER));
  }

  @Test
  void dependsOnTheServerSecret() {
    PseudonymKeyService other =
        PseudonymKeyService.fromSecret("another-sufficiently-long-secret-987654");

    assertThat(service.sortKey(DOC, USER)).isNotEqualTo(other.sortKey(DOC, USER));
  }
}
