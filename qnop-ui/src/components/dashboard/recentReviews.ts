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

const KEY = 'qnop-recent-reviews';
const MAX_RECENTS = 4;

/** One "continue where you left off" entry (issue #454) — device-local by design. */
export interface RecentReview {
  id: string;
  slug?: string | null;
  title: string;
}

/** The last opened reviews, newest first — [] when none or storage is unavailable. */
export function readRecentReviews(): RecentReview[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (entry): entry is RecentReview =>
        typeof entry === 'object' &&
        entry !== null &&
        typeof (entry as RecentReview).id === 'string' &&
        typeof (entry as RecentReview).title === 'string',
    );
  } catch {
    return [];
  }
}

/** Records a visit, deduplicated by id, newest first, capped. Best effort. */
export function recordRecentReview(entry: RecentReview) {
  try {
    const rest = readRecentReviews().filter((existing) => existing.id !== entry.id);
    localStorage.setItem(KEY, JSON.stringify([entry, ...rest].slice(0, MAX_RECENTS)));
  } catch {
    // best-effort persistence
  }
}
