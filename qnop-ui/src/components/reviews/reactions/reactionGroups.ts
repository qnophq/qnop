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

import type { ReactionGroup } from '../../../api/generated';

/**
 * The optimistic half of a reaction toggle (issue #410): what the server will
 * answer, computed locally so the chip flips before the round-trip. Adding to
 * an existing group bumps its count and appends the viewer; removing decrements
 * and drops the whole group at zero. Immutably — the caches keep their
 * snapshots for the rollback.
 */
export function toggleReactionGroup(
  groups: ReactionGroup[],
  emoji: string,
  viewerName: string,
): ReactionGroup[] {
  const existing = groups.find((group) => group.emoji === emoji);
  if (!existing) {
    return [...groups, { emoji, count: 1, reactedByMe: true, reactors: [viewerName] }];
  }
  if (!existing.reactedByMe) {
    return groups.map((group) =>
      group.emoji === emoji
        ? {
            ...group,
            count: group.count + 1,
            reactedByMe: true,
            reactors: [...group.reactors, viewerName],
          }
        : group,
    );
  }
  if (existing.count <= 1) {
    return groups.filter((group) => group.emoji !== emoji);
  }
  return groups.map((group) =>
    group.emoji === emoji
      ? {
          ...group,
          count: group.count - 1,
          reactedByMe: false,
          // Best effort: the server resolves the authoritative list on refetch.
          reactors: group.reactors.filter((name) => name !== viewerName),
        }
      : group,
  );
}
