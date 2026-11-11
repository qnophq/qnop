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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PseudonymOrdinalsTest {

  private static final UUID A = UUID.fromString("00000000-0000-7000-8000-000000000001");
  private static final UUID B = UUID.fromString("00000000-0000-7000-8000-000000000002");
  private static final UUID C = UUID.fromString("00000000-0000-7000-8000-000000000003");

  /** A sort key that reverses the id order, to prove the id order is NOT what is used. */
  private static Function<UUID, byte[]> keys(Map<UUID, Integer> rank) {
    return id -> new byte[] {(byte) (int) rank.get(id)};
  }

  @Test
  void ordersNewcomersBySortKeyNotById() {
    Map<UUID, Integer> result =
        PseudonymOrdinals.extend(Map.of(), List.of(A, B, C), keys(Map.of(A, 3, B, 2, C, 1)));

    assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(C, 1, B, 2, A, 3));
  }

  @Test
  void keepsExistingOrdinalsAndAppendsNewcomers() {
    // C joins later with the smallest sort key — it must NOT take label 1 from A.
    Map<UUID, Integer> assigned = Map.of(A, 1, B, 2);

    Map<UUID, Integer> result =
        PseudonymOrdinals.extend(assigned, List.of(A, B, C), keys(Map.of(A, 9, B, 8, C, 0)));

    assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(A, 1, B, 2, C, 3));
  }

  @Test
  void neverReusesTheLabelOfAPrincipalWhoLeft() {
    Map<UUID, Integer> assigned = Map.of(A, 1, B, 2);

    Map<UUID, Integer> result =
        PseudonymOrdinals.extend(assigned, List.of(A, C), keys(Map.of(A, 1, C, 1)));

    assertThat(result).containsEntry(C, 3).containsEntry(B, 2);
  }

  @Test
  void doesNotModifyTheInput() {
    Map<UUID, Integer> assigned = new HashMap<>(Map.of(A, 1));

    PseudonymOrdinals.extend(assigned, List.of(A, B, B), keys(Map.of(A, 1, B, 1)));

    assertThat(assigned).containsExactlyEntriesOf(Map.of(A, 1));
  }

  @Test
  void comparesSortKeysUnsigned() {
    Map<UUID, Integer> result =
        PseudonymOrdinals.extend(
            Map.of(),
            List.of(A, B),
            id -> id.equals(A) ? new byte[] {(byte) 0x80} : new byte[] {0x01});

    assertThat(result).containsEntry(B, 1).containsEntry(A, 2);
  }
}
