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
 * A user's role <em>within a team</em> (issue #105) — distinct from the global {@link UserRole} and
 * from per-review roles. Carried on {@link TeamMembership}. The allowed set is pinned by a Postgres
 * {@code CHECK} constraint in Liquibase, not here (ADR-0020).
 */
public enum TeamRole {
  /** Manages the team and its membership; the team's point of contact. */
  LEAD,
  /** A regular team member. The default when adding someone to a team. */
  MEMBER
}
