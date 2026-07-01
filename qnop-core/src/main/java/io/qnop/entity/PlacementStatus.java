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
package io.qnop.entity;

/**
 * The re-anchoring lifecycle of an {@link AnnotationPlacement} on a specific document version
 * (issue #244, ADR-0009). When a new version is ingested, each annotation's placement is re-located
 * from its anchor: it starts {@link #PENDING} and the re-anchoring job (#248) resolves it to one of
 * the terminal states. A closed set, pinned by a Postgres {@code CHECK} in Liquibase (ADR-0020).
 */
public enum PlacementStatus {
  /** Awaiting (re-)anchoring on this version. */
  PENDING,
  /** Anchored cleanly at the resolved location. */
  PLACED,
  /** Anchored, but the location shifted from the previous version. */
  MOVED,
  /** The anchored content no longer exists in this version; needs human attention. */
  ORPHANED,
  /** Re-anchoring failed (e.g. the anchor could not be interpreted). */
  FAILED
}
