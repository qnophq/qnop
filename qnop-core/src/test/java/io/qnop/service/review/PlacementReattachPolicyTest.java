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
package io.qnop.service.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.qnop.entity.PlacementStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The full setting × status × actor matrix for re-attach eligibility (issues #457/#479/#562):
 * PENDING never; lost placements always (outer authorization already ran); PLACED for admins
 * unconditionally and for authors only under the free-re-attach switch.
 */
class PlacementReattachPolicyTest {

  @ParameterizedTest(name = "{0} author={1} admin={2} free={3} -> {4}")
  @CsvSource({
    // PENDING: never, for anyone, in any setting state.
    "PENDING, true,  true,  true,  false",
    "PENDING, true,  false, true,  false",
    "PENDING, false, true,  false, false",
    // PLACED: admin always; author only when the switch is on; owner never.
    "PLACED,  false, true,  false, true",
    "PLACED,  false, true,  true,  true",
    "PLACED,  true,  false, true,  true",
    "PLACED,  true,  false, false, false",
    "PLACED,  false, false, true,  false",
    "PLACED,  false, false, false, false",
    // Lost placements: always eligible regardless of actor flags and setting.
    "ORPHANED, false, false, false, true",
    "FAILED,   false, false, false, true",
    "MOVED,    false, false, false, true",
    "ORPHANED, true,  true,  true,  true",
  })
  void matrix(PlacementStatus status, boolean author, boolean admin, boolean free, boolean want) {
    assertEquals(want, PlacementReattachPolicy.reattachEligible(status, author, admin, free));
  }
}
