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

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Pure, DB-free pseudonym ordinal assignment for anonymous reviews (issue #914, ADR-0038
 * amendment).
 *
 * <p>Ordinals already assigned (persisted) never change. Principals without one are appended after
 * the current maximum, ordered among themselves by a keyed sort key (an HMAC — never the raw id),
 * so neither the labels nor their order are derivable from user ids or account age, and a newcomer
 * never shifts an existing label.
 */
final class PseudonymOrdinals {

  private PseudonymOrdinals() {}

  /**
   * Returns the ordinals of all of {@code principals}: {@code assigned} entries unchanged, plus
   * fresh ordinals for the rest. The returned map is new; {@code assigned} is not modified. Entries
   * of {@code assigned} for principals no longer in {@code principals} are kept (a label is never
   * reused).
   */
  static Map<UUID, Integer> extend(
      Map<UUID, Integer> assigned, Collection<UUID> principals, Function<UUID, byte[]> sortKey) {
    Map<UUID, Integer> result = new HashMap<>(assigned);
    int max = assigned.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    List<UUID> newcomers =
        principals.stream()
            .distinct()
            .filter(id -> !assigned.containsKey(id))
            .map(id -> Map.entry(id, sortKey.apply(id)))
            .sorted(Comparator.comparing(Map.Entry::getValue, Arrays::compareUnsigned))
            .map(Map.Entry::getKey)
            .toList();
    for (UUID id : newcomers) {
      result.put(id, ++max);
    }
    return result;
  }
}
