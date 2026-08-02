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
package io.qnop.service.limits;

/**
 * The four things a deployment can run out of (issue #673).
 *
 * <p>Each carries the error code clients see and the sentence a person reads. Keeping both here
 * means a new quota is one enum constant rather than a string repeated across services, and that
 * the message always names the number — an administrator who is refused needs to know what the
 * ceiling is, not merely that they reached it.
 */
public enum InstanceLimit {
  USERS("USER_LIMIT_EXCEEDED", "user accounts"),
  TEAMS("TEAM_LIMIT_EXCEEDED", "teams"),
  TEAM_MEMBERS("TEAM_MEMBER_LIMIT_EXCEEDED", "members in one team"),
  ACTIVE_REVIEWS("ACTIVE_REVIEW_LIMIT_EXCEEDED", "active reviews");

  private final String code;
  private final String subject;

  InstanceLimit(String code, String subject) {
    this.code = code;
    this.subject = subject;
  }

  public String code() {
    return code;
  }

  public String subject() {
    return subject;
  }

  String describe(int maximum) {
    return "this deployment allows " + maximum + " " + subject;
  }
}
