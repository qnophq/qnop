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
 * The capacity in which a {@link ReviewParticipant} takes part in a review (issue #244, ADR-0011).
 *
 * <p>The canonical owner is modelled structurally as {@code Document.ownerId} (a non-null user FK),
 * which guarantees exactly one owner. This role therefore describes participants beyond that owner;
 * {@link #REVIEWER} is the ordinary case, and {@link #OWNER} is reserved for future co-ownership or
 * owner-delegation without a schema change. A closed set, pinned by a Postgres {@code CHECK} in
 * Liquibase (ADR-0020).
 */
public enum ParticipantRole {
  /**
   * Reserved: a co-owner or owner-delegate. The authoritative owner is {@code Document.ownerId}.
   */
  OWNER,
  /** Takes part to annotate and comment; the ordinary participant. */
  REVIEWER
}
