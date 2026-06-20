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
 * A {@link User}'s global system role — exactly one per user. This is the application-wide
 * authorization level; it is distinct from per-review roles (owner/reviewer) and from team roles
 * ({@code LEAD}/{@code MEMBER}), which are modelled separately.
 *
 * <p>The role is carried as a claim in the access token and mapped to a Spring Security {@code
 * ROLE_*} authority. The allowed set is pinned by a Postgres {@code CHECK} constraint in Liquibase,
 * not here (ADR-0020).
 */
public enum UserRole {
  /**
   * System administration: users, teams, settings, OIDC, branding, mail. Gates {@code /admin/**}.
   */
  ADMIN,
  /** Standard user: create and take part in reviews. The default for new accounts. */
  MEMBER,
  /** Like {@code MEMBER} plus organisation-wide read access to the audit trail and compliance. */
  AUDITOR
}
