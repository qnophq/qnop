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

import type { TrackerAdapter } from './providers';

/**
 * Everything qnop measures beyond page views (issue #666).
 *
 * <p>A closed union, not an open string. An `event(name, properties)` API would eventually carry a
 * document title, a reviewer's name or a search term into somebody's analytics backend — not out of
 * carelessness, but because that is what an open API invites. Four names, no properties, and
 * adding a fifth is a code change somebody reviews.
 */
export type TrackedEvent =
  'review_created' | 'annotation_created' | 'review_finalized' | 'export_generated';

let active: TrackerAdapter | null = null;

/** Set by {@code TrackingBoot} once — and only if — a tracker actually loaded. */
export function setActiveTracker(adapter: TrackerAdapter | null): void {
  active = adapter;
}

/**
 * Reports that something happened. Does nothing at all when no tracker is loaded, which is the
 * usual case: measurement is off by default, and every gate can veto it.
 */
export function trackEvent(event: TrackedEvent): void {
  try {
    active?.event(event);
  } catch {
    // A failed measurement must never surface in a review.
  }
}
