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
package io.qnop.service;

/**
 * The acting user may not manage this team — the non-admin team-lead surface (issue #470) is
 * limited to a {@code LEAD} of the team (or a global {@code ADMIN}). A framework-light signal so
 * the service layer stays free of {@code spring-security-web}; the web layer maps it to HTTP 403.
 */
public class TeamAccessForbiddenException extends RuntimeException {

  private final String code;

  public TeamAccessForbiddenException(String message) {
    this("TEAM_ACCESS_FORBIDDEN", message);
  }

  /** With a more specific stable code, e.g. {@code LEAD_GRANT_REQUIRES_ADMIN} (issue #936). */
  public TeamAccessForbiddenException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
